package io.github.marcocavedon.provisio.forecasting.worker;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.Product;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * {@code provisio.forecasting.run-batch} end to end, across both languages (ADR 0030): a Camunda engine in Docker
 * runs a one-task test process, the starter invokes the real worker, the worker reads MongoDB and calls the real
 * forecasting service, built from {@code forecasting/Dockerfile} in a container.
 *
 * <p>It is the consumer-side contract test: the Java request generated from the contract must pass the Python
 * service's validation. The service has no forecasting method yet, so every product comes back {@code FAILED} with
 * {@code METHOD_NOT_IMPLEMENTED}, which exercises the failure path; the OK mapping is covered by
 * {@code ForecastingClientTests}. Requires Docker; the first run builds the service image.
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
class RunForecastBatchWorkerProcessTests {

  static final String PROCESS_RESOURCE = "bpmn/forecasting-run-batch-test.bpmn";
  static final String PROCESS_ID = "forecasting-run-batch-test";

  /** The forecasting service as Compose runs it; the image is kept between runs, so only the first run builds it. */
  @SuppressWarnings("resource") // stopped by Testcontainers when the JVM exits
  static final GenericContainer<?> FORECASTING = new GenericContainer<>(
      new ImageFromDockerfile("provisio-forecasting-test", false)
          .withDockerfile(Path.of("../forecasting/Dockerfile")))
      .withExposedPorts(8000)
      .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  static {
    FORECASTING.start();
  }

  @DynamicPropertySource
  static void forecastingService(DynamicPropertyRegistry registry) {
    registry.add("provisio.forecasting.base-url",
        () -> "http://" + FORECASTING.getHost() + ":" + FORECASTING.getMappedPort(8000));
  }

  @Autowired
  private CamundaClient client;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private DemandImport demandImport;

  @Autowired
  private InventoryOperations inventoryOperations;

  @BeforeEach
  void setUp() {
    // The engine's data is reset between tests, so the process is deployed for each one
    client.newDeployResourceCommand().addResourceFromClasspath(PROCESS_RESOURCE).send().join();
    for (String collection : new String[] {"forecasts", "forecast_runs", "demand_history", "business_calendar",
        "products", "stock_movements", "stock_levels"}) {
      mongoTemplate.remove(new Query(), collection);
    }
    inventoryOperations.registerProducts(List.of(new Product("A", "BAG A", "BAGS", "EA"),
        new Product("B", "BAG B", "BAGS", "EA")));
    // Observed in June 2011, planned to the end of August; closed on Saturdays 4 June and 2 July
    demandImport.replaceAll(new BusinessCalendar(LocalDate.of(2011, 6, 1), LocalDate.of(2011, 6, 30),
            LocalDate.of(2011, 8, 31), Set.of(LocalDate.of(2011, 6, 4), LocalDate.of(2011, 7, 2))),
        List.of(new DemandEntry("A", LocalDate.of(2011, 6, 1), 5), new DemandEntry("B", LocalDate.of(2011, 6, 30), 2)));
  }

  @Test
  void runsTheBatchAgainstTheRealServiceAndStoresEveryOutcome() {
    ProcessInstanceEvent instance = start(Map.of("asOf", "2011-07-01"));

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariableSatisfies("forecastRun", Map.class, run -> assertThat(run)
            .containsEntry("asOf", "2011-07-01")
            .containsEntry("horizonEnd", "2011-07-28")
            .containsEntry("requested", 2)
            .containsEntry("ok", 0)
            .containsEntry("failed", 2));

    List<Document> forecasts = mongoTemplate.getCollection("forecasts").find().into(new ArrayList<>());
    assertThat(forecasts).hasSize(2).allSatisfy(forecast -> {
      assertThat(forecast.getString("outcome")).isEqualTo("FAILED");
      assertThat(forecast.get("failure", Document.class).getString("code")).isEqualTo("METHOD_NOT_IMPLEMENTED");
      assertThat(forecast.get("service", Document.class).getString("version")).isEqualTo("0.1.0");
    });
    Document run = mongoTemplate.getCollection("forecast_runs").find().first();
    assertThat(run).isNotNull();
    assertThat(run.getString("status")).isEqualTo("COMPLETED");
    assertThat(run.getLong("processInstanceKey")).isEqualTo(instance.getProcessInstanceKey());
  }

  @Test
  void missingBusinessDateRaisesAnIncidentAtOnce() {
    ProcessInstanceEvent instance = start(Map.of());

    assertThatProcessInstance(instance).isActive().hasActiveIncidents();
    assertThat(mongoTemplate.getCollection("forecast_runs").countDocuments()).isZero();
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
