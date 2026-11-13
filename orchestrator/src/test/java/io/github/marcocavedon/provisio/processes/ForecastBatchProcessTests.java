package io.github.marcocavedon.provisio.processes;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static io.camunda.process.test.api.assertions.ElementSelectors.byName;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.ProcessInstanceSelectors;
import io.camunda.process.test.api.mock.JobWorkerMockBuilder.JobWorkerMock;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The product process {@code provisio-forecast-batch} and its decision {@code provisio-forecast-run-assessment}, as
 * deployed at startup from the top-level {@code processes/} folder (ADR 0025, ADR 0030, ADR 0031).
 *
 * <p>It tests the model, not the workers: the application's job workers are disabled and every job type is a mock
 * that returns what the real worker would. The workers have their own tests. The decision table is the real one: each
 * of its rules, and the boundaries between them, is reached through the process, which is how it is evaluated in
 * production. Elements are selected by name, the names an operator reads in Operate. Requires Docker.
 *
 * <p>Its own Spring context (job workers disabled), so it does not share the context of the worker process tests.
 */
@SpringBootTest(properties = {
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container",
    // The model is under test: the job types are mocked, the real workers must not take their jobs
    "camunda.client.worker.defaults.enabled=false",
    // One Camunda runtime for every process test class, instead of one per class
    "camunda.process-test.runtime-mode=shared",
    // Same engine version as the local stack: keep in sync with CAMUNDA_VERSION in docker-compose.yml
    "camunda.process-test.camunda-docker-image-version=8.9.23"
})
@CamundaSpringProcessTest
@Import(MongoTestcontainersConfiguration.class)
class ForecastBatchProcessTests {

  static final String PROCESS_ID = "provisio-forecast-batch";
  static final String MESSAGE = "provisio.forecasting.batch-requested";

  private static final String BUSINESS_DATE = "2011-07-01";

  @Autowired
  private CamundaClient client;

  @Autowired
  private CamundaProcessTestContext processTest;

  @BeforeEach
  void deploy() {
    // The engine's data is reset between tests; both models in one deployment, as at startup (binding "deployment")
    client.newDeployResourceCommand()
        .addResourceFromClasspath("processes/provisio-forecast-batch.bpmn")
        .addResourceFromClasspath("processes/provisio-forecast-run-assessment.dmn")
        .send()
        .join();
  }

  @Test
  void theTimerComputesTheBusinessDateThatHasNoCompletedRun() {
    processTest.mockJobWorker("provisio.time.get-business-date").thenComplete(Map.of("businessDate", BUSINESS_DATE));
    JobWorkerMock lookup = processTest.mockJobWorker("provisio.forecasting.find-completed-run")
        .thenComplete(Map.of("completedRun", Map.of("found", false, "asOf", BUSINESS_DATE)));
    JobWorkerMock batch = processTest.mockJobWorker("provisio.forecasting.run-batch")
        .thenComplete(Map.of("forecastRun", forecastRun(54, 54, 0)));

    // The cron fires once a day at 01:00 UTC: one day later it has fired exactly once
    processTest.increaseTime(Duration.ofDays(1));

    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID))
        .isCompleted()
        .hasCompletedElementsInOrder(byName("Get business date"), byName("Find completed run"),
            byName("Run forecast batch"), byName("Assess forecast run"), byName("Forecasts available"))
        .hasVariable("asOf", BUSINESS_DATE);
    // Both tasks see the business date from the process scope, not a value of their own
    assertThat(lookup.getActivatedJobs()).singleElement()
        .satisfies(job -> assertThat(job.getVariablesAsMap()).containsEntry("asOf", BUSINESS_DATE));
    assertThat(batch.getActivatedJobs()).singleElement()
        .satisfies(job -> assertThat(job.getVariablesAsMap()).containsEntry("asOf", BUSINESS_DATE));
  }

  @Test
  void theTimerSkipsABusinessDateThatAlreadyHasACompletedRun() {
    processTest.mockJobWorker("provisio.time.get-business-date").thenComplete(Map.of("businessDate", BUSINESS_DATE));
    processTest.mockJobWorker("provisio.forecasting.find-completed-run")
        .thenComplete(Map.of("completedRun", Map.of("found", true, "runId", "batch-1", "asOf", BUSINESS_DATE)));
    JobWorkerMock batch = processTest.mockJobWorker("provisio.forecasting.run-batch")
        .thenComplete(Map.of("forecastRun", forecastRun(54, 54, 0)));

    processTest.increaseTime(Duration.ofDays(1));

    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID))
        .isCompleted()
        .hasCompletedElements(byName("Already forecast"))
        .hasNotActivatedElements(byName("Run forecast batch"), byName("Assess forecast run"));
    assertThat(batch.getInvocations()).isZero();
  }

  @Test
  void aRequestedRunAlwaysComputesWithoutLookingForACompletedRun() {
    JobWorkerMock lookup = processTest.mockJobWorker("provisio.forecasting.find-completed-run")
        .thenComplete(Map.of("completedRun", Map.of("found", true, "runId", "batch-1", "asOf", "2011-12-08")));
    processTest.mockJobWorker("provisio.forecasting.run-batch")
        .thenComplete(Map.of("forecastRun", forecastRun(54, 0, 54)));

    request("2011-12-08");

    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID))
        .isCompleted()
        .hasCompletedElements(byName("Run forecast batch"), byName("Forecasts unusable"))
        .hasNotActivatedElements(byName("Get business date"), byName("Find completed run"))
        .hasVariable("asOf", "2011-12-08");
    assertThat(lookup.getInvocations()).isZero();
  }

  @Test
  void requestsForTheSameDateWhileARunIsActiveStartOneInstance() {
    // No run-batch mock yet: the first instance waits on the batch
    request("2011-12-08");
    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID))
        .hasActiveElements(byName("Run forecast batch"));

    // Same correlation key while the first instance is active: no second instance
    request("2011-12-08");
    processTest.mockJobWorker("provisio.forecasting.run-batch")
        .thenComplete(Map.of("forecastRun", forecastRun(54, 0, 54)));

    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID)).isCompleted();
    assertThat(client.newProcessInstanceSearchRequest()
        .filter(filter -> filter.processDefinitionId(PROCESS_ID))
        .send()
        .join()
        .items())
        .hasSize(1);
  }

  /**
   * Every rule of the decision table and the boundaries between them (thresholds provisional until R2), reached
   * through the process: the outcome selects the end event, the reason counts what it describes.
   */
  @ParameterizedTest(name = "requested {0}, ok {1}, failed {2}: {3}")
  @CsvSource(delimiter = '|', textBlock = """
      54 | 54 |  0 | healthy  | Forecasts available | Forecasts available for nearly all products (54 of 54 with a forecast).
      50 | 45 |  5 | healthy  | Forecasts available | Forecasts available for nearly all products (45 of 50 with a forecast).
      50 | 44 |  6 | degraded | Forecasts degraded  | Forecasts missing for a significant share of products: check the failure codes before relying on them (6 of 50 without a forecast).
      50 | 25 | 25 | degraded | Forecasts degraded  | Forecasts missing for a significant share of products: check the failure codes before relying on them (25 of 50 without a forecast).
      50 | 24 | 26 | unusable | Forecasts unusable  | Forecasts missing for most products: the run cannot support replenishment decisions (26 of 50 without a forecast).
      54 |  0 | 54 | unusable | Forecasts unusable  | Forecasts missing for most products: the run cannot support replenishment decisions (54 of 54 without a forecast).
       0 |  0 |  0 | unusable | Forecasts unusable  | No products were requested: check the seed and the product catalogue.
      """)
  void theAssessmentOfTheRunSelectsTheEndEvent(int requested, int ok, int failed, String outcome, String endEvent,
      String reason) {
    processTest.mockJobWorker("provisio.forecasting.run-batch")
        .thenComplete(Map.of("forecastRun", forecastRun(requested, ok, failed)));

    request("2011-12-08");

    assertThatProcessInstance(ProcessInstanceSelectors.byProcessId(PROCESS_ID))
        .isCompleted()
        .hasCompletedElements(byName(endEvent))
        .hasVariable("forecastRunAssessment", Map.of("outcome", outcome, "reason", reason));
  }

  /** Publishes the start message as a manual run does: correlation key and variable both {@code asOf}. */
  private void request(String asOf) {
    client.newPublishMessageCommand()
        .messageName(MESSAGE)
        .correlationKey(asOf)
        .timeToLive(Duration.ZERO)
        .variables(Map.of("asOf", asOf))
        .send()
        .join();
  }

  /** The output of {@code provisio.forecasting.run-batch}, as the real worker builds it. */
  private static Map<String, Object> forecastRun(int requested, int ok, int failed) {
    return Map.of("runId", "batch-1", "asOf", "2011-12-08", "horizonEnd", "2012-01-04",
        "requested", requested, "ok", ok, "failed", failed);
  }
}
