package io.github.marcocavedon.provisio.inventory.internal;

import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.adjustment;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.givenProduct;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.issue;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.MovementResult;
import io.github.marcocavedon.provisio.inventory.MovementResult.Reason;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The consistency check of ADR 0022: for every SKU, {@code onHand} equals the sum of its movements' deltas.
 *
 * <p>A long, seeded random sequence mixes receipts, issues and adjustments with rejected movements, unknown SKUs and
 * retried commands, so that every path of the write path contributes to the ledger being checked. The seed is fixed:
 * a failure is reproducible. The aggregation lives here for now; it moves to production code when it is needed as an
 * operational report. Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class InventoryReconciliationTests {

  private static final List<String> SKUS = List.of("R-1", "R-2", "R-3", "R-4");
  private static final int OPERATIONS = 300;

  @Autowired
  private InventoryOperations operations;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    InventoryTestSupport.clear(mongoTemplate);
    SKUS.forEach(sku -> givenProduct(mongoTemplate, sku));
  }

  @Test
  void stockLevelsEqualTheSumOfTheirMovements() {
    Random random = new Random(42);
    List<RecordMovement> recordedCommands = new ArrayList<>();

    for (int i = 0; i < OPERATIONS; i++) {
      int dice = random.nextInt(20);
      if (dice == 0 && !recordedCommands.isEmpty()) {
        // A retried job: same command, same key
        RecordMovement retried = recordedCommands.get(random.nextInt(recordedCommands.size()));
        assertThat(operations.recordMovement(retried))
            .isInstanceOfSatisfying(MovementResult.Recorded.class, r -> assertThat(r.alreadyRecorded()).isTrue());
        continue;
      }
      if (dice == 1) {
        assertThat(operations.recordMovement(receipt("R-UNKNOWN", 1, "unknown-" + i)))
            .isEqualTo(new MovementResult.Rejected(Reason.UNKNOWN_SKU));
        continue;
      }

      RecordMovement command = randomMovement(random, i);
      MovementResult result = operations.recordMovement(command);
      if (result instanceof MovementResult.Recorded) {
        recordedCommands.add(command);
      } else {
        assertThat(result).isEqualTo(new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK));
      }
    }

    Map<String, Integer> ledgerTotals = sumOfDeltasPerSku();
    Map<String, Integer> onHand = mongoTemplate.findAll(StockLevelDocument.class).stream()
        .collect(Collectors.toMap(StockLevelDocument::sku, StockLevelDocument::onHand));

    assertThat(onHand).as("onHand per SKU equals the sum of its deltas").isEqualTo(ledgerTotals);
    assertThat(onHand.values()).allSatisfy(quantity -> assertThat(quantity).isNotNegative());
    assertThat(mongoTemplate.count(new Query(), StockMovementDocument.class)).isEqualTo(recordedCommands.size());
  }

  private static RecordMovement randomMovement(Random random, int i) {
    String sku = SKUS.get(random.nextInt(SKUS.size()));
    String key = "op-" + i;
    return switch (random.nextInt(3)) {
      case 0 -> receipt(sku, 1 + random.nextInt(10), key);
      case 1 -> issue(sku, 1 + random.nextInt(8), key);
      default -> adjustment(sku, random.nextBoolean() ? 1 + random.nextInt(5) : -(1 + random.nextInt(5)), key);
    };
  }

  private Map<String, Integer> sumOfDeltasPerSku() {
    return mongoTemplate.aggregate(
            newAggregation(group("sku").sum("delta").as("total")),
            StockMovementDocument.COLLECTION,
            Document.class)
        .getMappedResults().stream()
        .collect(Collectors.toMap(
            document -> document.getString("_id"),
            document -> document.get("total", Number.class).intValue()));
  }
}
