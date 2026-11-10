package io.github.marcocavedon.provisio.forecasting.worker;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy.LeadTime;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastRunDocument;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@code provisio.forecasting.find-completed-run} through the real invocation path: a Camunda engine in Docker runs a
 * one-task test process, the starter invokes the real worker, the worker reads {@code forecast_runs} in MongoDB (ADR
 * 0030). The forecasting service is not needed. Requires Docker.
 *
 * <p>Same properties as the other process tests that need no forecasting service, so they share one Spring context.
 *
 * <p>The test process {@value #PROCESS_RESOURCE}: process id {@value #PROCESS_ID}, start event, one service task of
 * type {@code provisio.forecasting.find-completed-run} with no input or output mappings, end event. It lives under test
 * resources: it is not a product process.
 */
@SpringBootTest(properties = {
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container",
    // One Camunda runtime for every process test class, instead of one per class
    "camunda.process-test.runtime-mode=shared",
    // Same engine version as the local stack: keep in sync with CAMUNDA_VERSION in docker-compose.yml
    "camunda.process-test.camunda-docker-image-version=8.9.23"
})
@CamundaSpringProcessTest
@Import(MongoTestcontainersConfiguration.class)
class FindCompletedRunWorkerProcessTests {

  static final String PROCESS_RESOURCE = "bpmn/forecasting-find-completed-run-test.bpmn";
  static final String PROCESS_ID = "forecasting-find-completed-run-test";

  private static final LocalDate AS_OF = LocalDate.of(2011, 7, 1);
  private static final Instant COMPLETED_AT = Instant.parse("2026-11-10T01:00:12.345Z");

  @Autowired
  private CamundaClient client;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    // The engine's data is reset between tests, so the process is deployed for each one
    client.newDeployResourceCommand().addResourceFromClasspath(PROCESS_RESOURCE).send().join();
    mongoTemplate.remove(new Query(), ForecastRunDocument.COLLECTION);
    ReplenishmentPolicy policy = new ReplenishmentPolicy(new LeadTime(14, null), 7, 0.95);
    mongoTemplate.insert(new ForecastRunDocument("batch-1", AS_OF, LocalDate.of(2011, 7, 28), policy, 1L, 2L,
        COMPLETED_AT.minusSeconds(30), COMPLETED_AT, 1, "COMPLETED", 54, 0, 54));
    mongoTemplate.insert(new ForecastRunDocument("batch-2", AS_OF.plusDays(1), LocalDate.of(2011, 7, 29), policy, 3L,
        4L, COMPLETED_AT, null, 1, "RUNNING", 54, null, null));
  }

  @Test
  void findsTheCompletedRunOfTheDate() {
    ProcessInstanceEvent instance = start(Map.of("asOf", "2011-07-01"));

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("completedRun", Map.of("found", true, "runId", "batch-1", "asOf", "2011-07-01",
            "horizonEnd", "2011-07-28", "requested", 54, "ok", 0, "failed", 54,
            "completedAt", "2026-11-10T01:00:12.345Z"));
  }

  @Test
  void aRunStillRunningIsNotFound() {
    ProcessInstanceEvent instance = start(Map.of("asOf", "2011-07-02"));

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("completedRun", Map.of("found", false, "asOf", "2011-07-02"));
  }

  @Test
  void missingBusinessDateRaisesAnIncidentAtOnce() {
    ProcessInstanceEvent instance = start(Map.of());

    assertThatProcessInstance(instance).isActive().hasActiveIncidents();
  }

  private ProcessInstanceEvent start(Map<String, Object> variables) {
    return client.newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .variables(variables)
        .send()
        .join();
  }
}
