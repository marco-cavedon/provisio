package io.github.marcocavedon.provisio.time.worker;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code provisio.time.get-business-date} through the real invocation path: a Camunda engine in Docker runs a one-task
 * test process and the starter invokes the real worker (ADR 0031). It catches a non-public worker class, a wrong job
 * type, or an output serialised differently from the map the worker builds. Requires Docker.
 *
 * <p>The application's own configuration is used (the mapped mode), with the same properties as the other process
 * tests so they share one Spring context: the expected value is whatever the business clock says today.
 *
 * <p>The test process {@value #PROCESS_RESOURCE}: process id {@value #PROCESS_ID}, start event, one service task of
 * type {@code provisio.time.get-business-date} with no input or output mappings, end event. It lives under test
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
class GetBusinessDateWorkerProcessTests {

  static final String PROCESS_RESOURCE = "bpmn/time-get-business-date-test.bpmn";
  static final String PROCESS_ID = "time-get-business-date-test";

  @Autowired
  private CamundaClient client;

  @Autowired
  private BusinessClock businessClock;

  @BeforeEach
  void deploy() {
    // The engine's data is reset between tests, so the process is deployed for each one
    client.newDeployResourceCommand().addResourceFromClasspath(PROCESS_RESOURCE).send().join();
  }

  @Test
  void setsTheBusinessDateAsAProcessVariable() {
    ProcessInstanceEvent instance = client.newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("businessDate", businessClock.today().toString());
  }
}
