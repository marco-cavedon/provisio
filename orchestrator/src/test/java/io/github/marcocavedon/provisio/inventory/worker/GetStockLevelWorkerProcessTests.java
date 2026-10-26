package io.github.marcocavedon.provisio.inventory.worker;

import static io.camunda.process.test.api.CamundaAssert.assertThatProcessInstance;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.MovementResult;
import io.github.marcocavedon.provisio.inventory.MovementType;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@code provisio.inventory.get-stock-level} through the real invocation path: a Camunda engine in Docker runs a
 * one-task test process, the starter invokes the real worker, the worker reads MongoDB (also in Docker).
 *
 * <p>It catches what a unit test cannot: a non-public worker class, a wrong job type, a variable that is not fetched,
 * an output that is serialised differently from the map the worker builds. {@code hasVariable} compares JSON values,
 * so an omitted field and a {@code null} field are different. Requires Docker.
 *
 * <p>The test process {@value #PROCESS_RESOURCE} is drawn in Camunda Desktop Modeler: process id
 * {@value #PROCESS_ID}, start event, one service task of type {@code provisio.inventory.get-stock-level} with no
 * input or output mappings, end event. It lives under test resources, not in {@code processes/}: it is not a product
 * process and is never deployed by the application.
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
class GetStockLevelWorkerProcessTests {

  static final String PROCESS_RESOURCE = "bpmn/inventory-get-stock-level-test.bpmn";
  static final String PROCESS_ID = "inventory-get-stock-level-test";

  @Autowired
  private CamundaClient client;

  @Autowired
  private InventoryOperations operations;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    // The engine's data is reset between tests, so the process is deployed for each one
    client.newDeployResourceCommand().addResourceFromClasspath(PROCESS_RESOURCE).send().join();
    for (String collection : new String[] {"products", "stock_movements", "stock_levels"}) {
      mongoTemplate.remove(new Query(), collection);
    }
  }

  @Test
  void returnsTheStockLevelOfAKnownSku() {
    mongoTemplate.insert(Map.of("_id", "SKU-1", "name", "Product SKU-1", "category", "FOODS", "unit", "EA"),
        "products");
    MovementResult result = operations.recordMovement(new RecordMovement(
        "SKU-1", MovementType.RECEIPT, 7, Instant.parse("2026-10-27T09:00:00Z"), "process-test:receipt", null, null));
    String asOf = ((MovementResult.Recorded) result).movement().recordedAt().toString();

    ProcessInstanceEvent instance = start(Map.of("sku", "SKU-1"));

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("stockLevel", Map.of("sku", "SKU-1", "found", true, "onHand", 7, "asOf", asOf));
  }

  @Test
  void returnsNotFoundForAnUnknownSku() {
    ProcessInstanceEvent instance = start(Map.of("sku", "NO-SUCH-SKU"));

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("stockLevel", Map.of("sku", "NO-SUCH-SKU", "found", false));
  }

  @Test
  void returnsNotFoundWhenTheSkuIsMissing() {
    ProcessInstanceEvent instance = start(Map.of());

    assertThatProcessInstance(instance)
        .isCompleted()
        .hasVariable("stockLevel", Map.of("found", false));
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
