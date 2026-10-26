package io.github.marcocavedon.provisio.inventory.internal;

import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.adjustment;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.givenProduct;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.issue;
import static io.github.marcocavedon.provisio.inventory.internal.InventoryTestSupport.receipt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.inventory.IdempotencyKeyConflictException;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.MovementResult;
import io.github.marcocavedon.provisio.inventory.MovementResult.Reason;
import io.github.marcocavedon.provisio.inventory.MovementType;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import io.github.marcocavedon.provisio.inventory.StockMovement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The write path of ADR 0022 against a real MongoDB replica set. Requires Docker.
 *
 * <p>The rejection tests are the ones that protect atomicity: a rejected movement is inserted first and then rolled
 * back, so if transactions were silently disabled they would find an orphan movement in the ledger.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class InventoryServiceTests {

  @Autowired
  private InventoryOperations operations;

  @Autowired
  private InventoryQueries queries;

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void setUp() {
    InventoryTestSupport.clear(mongoTemplate);
  }

  @Test
  void firstReceiptCreatesTheStockLevel() {
    givenProduct(mongoTemplate, "SKU-1");

    StockMovement movement = recorded(operations.recordMovement(receipt("SKU-1", 5, "po-1:line-1")));

    assertThat(movement.type()).isEqualTo(MovementType.RECEIPT);
    assertThat(movement.delta()).isEqualTo(5);
    assertThat(queries.findStockLevel("SKU-1")).contains(new StockLevel("SKU-1", 5, movement.recordedAt()));
    assertThat(stockLevelDocument("SKU-1").lastMovementId().toHexString()).isEqualTo(movement.id());
  }

  @Test
  void issueAndAdjustmentUpdateTheStockLevel() {
    givenProduct(mongoTemplate, "SKU-1");
    recorded(operations.recordMovement(receipt("SKU-1", 5, "receipt")));

    recorded(operations.recordMovement(issue("SKU-1", 2, "issue")));
    StockMovement last = recorded(operations.recordMovement(adjustment("SKU-1", -3, "count")));

    assertThat(queries.findStockLevel("SKU-1")).contains(new StockLevel("SKU-1", 0, last.recordedAt()));
    assertThat(stockLevelDocument("SKU-1").lastMovementId().toHexString()).isEqualTo(last.id());
  }

  @Test
  void issueBeyondOnHandIsRejectedAndLeavesNoTrace() {
    givenProduct(mongoTemplate, "SKU-1");
    StockMovement receipt = recorded(operations.recordMovement(receipt("SKU-1", 3, "receipt")));

    MovementResult result = operations.recordMovement(issue("SKU-1", 4, "too-much"));

    assertThat(result).isEqualTo(new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK));
    assertThat(movementCount("SKU-1")).as("the rejected movement was rolled back").isEqualTo(1);
    StockLevelDocument level = stockLevelDocument("SKU-1");
    assertThat(level.onHand()).isEqualTo(3);
    assertThat(level.lastMovementId().toHexString()).isEqualTo(receipt.id());
  }

  @Test
  void negativeFirstMovementIsRejectedAndCreatesNoStockLevel() {
    givenProduct(mongoTemplate, "SKU-1");

    MovementResult result = operations.recordMovement(adjustment("SKU-1", -1, "count"));

    assertThat(result).isEqualTo(new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK));
    assertThat(movementCount("SKU-1")).isZero();
    assertThat(mongoTemplate.findById("SKU-1", StockLevelDocument.class)).isNull();
  }

  @Test
  void unknownSkuIsRejectedAndWritesNothing() {
    MovementResult result = operations.recordMovement(receipt("NO-SUCH-SKU", 1, "receipt"));

    assertThat(result).isEqualTo(new MovementResult.Rejected(Reason.UNKNOWN_SKU));
    assertThat(mongoTemplate.count(new Query(), StockMovementDocument.class)).isZero();
    assertThat(mongoTemplate.findById("NO-SUCH-SKU", StockLevelDocument.class)).isNull();
  }

  @Test
  void sameIdempotencyKeyRecordsTheMovementOnce() {
    givenProduct(mongoTemplate, "SKU-1");
    RecordMovement command = receipt("SKU-1", 5, "po-1:line-1");

    MovementResult first = operations.recordMovement(command);
    MovementResult second = operations.recordMovement(command);

    StockMovement original = recorded(first);
    assertThat(second).isEqualTo(new MovementResult.Recorded(original, true));
    assertThat(movementCount("SKU-1")).isEqualTo(1);
    assertThat(stockLevelDocument("SKU-1").onHand()).isEqualTo(5);
  }

  @Test
  void sameIdempotencyKeyForADifferentMovementFails() {
    givenProduct(mongoTemplate, "SKU-1");
    recorded(operations.recordMovement(receipt("SKU-1", 5, "po-1:line-1")));

    assertThatThrownBy(() -> operations.recordMovement(receipt("SKU-1", 7, "po-1:line-1")))
        .isInstanceOf(IdempotencyKeyConflictException.class)
        .hasMessageContaining("po-1:line-1");

    assertThat(movementCount("SKU-1")).isEqualTo(1);
    assertThat(stockLevelDocument("SKU-1").onHand()).isEqualTo(5);
  }

  @Test
  void rejectedMovementCanBeRetriedWithTheSameKeyOnceStockArrives() {
    givenProduct(mongoTemplate, "SKU-1");
    RecordMovement issue = issue("SKU-1", 2, "order-7:line-1");
    assertThat(operations.recordMovement(issue)).isEqualTo(new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK));

    recorded(operations.recordMovement(receipt("SKU-1", 5, "receipt")));

    assertThat(operations.recordMovement(issue))
        .isInstanceOfSatisfying(MovementResult.Recorded.class, r -> assertThat(r.alreadyRecorded()).isFalse());
    assertThat(stockLevelDocument("SKU-1").onHand()).isEqualTo(3);
  }

  @Test
  void timesAreStoredWithMillisecondPrecision() {
    givenProduct(mongoTemplate, "SKU-1");
    Instant withNanos = Instant.parse("2026-10-01T10:00:00.123456789Z");
    RecordMovement command = new RecordMovement("SKU-1", MovementType.RECEIPT, 1, withNanos, "k", null, null);

    StockMovement returned = recorded(operations.recordMovement(command));
    StockMovementDocument stored = mongoTemplate.findOne(
        query(where("idempotencyKey").is("k")), StockMovementDocument.class);

    assertThat(returned.occurredAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00.123Z"));
    assertThat(stored.toStockMovement()).isEqualTo(returned);
  }

  @Test
  void stockLevelOfUnknownAndOfNeverMovedProducts() {
    givenProduct(mongoTemplate, "SKU-NEW");

    assertThat(queries.findStockLevel("NO-SUCH-SKU")).isEmpty();
    assertThat(queries.findStockLevel("SKU-NEW")).contains(new StockLevel("SKU-NEW", 0, null));
    assertThat(queries.findProduct("SKU-NEW")).hasValueSatisfying(p -> assertThat(p.unit()).isEqualTo("EA"));
    assertThat(queries.findProduct("NO-SUCH-SKU")).isEmpty();
  }

  @Test
  void skuThatLooksLikeAnObjectIdStaysAString() {
    String hexSku = "0123456789abcdef01234567";
    givenProduct(mongoTemplate, hexSku);

    recorded(operations.recordMovement(receipt(hexSku, 1, "k")));

    assertThat(queries.findStockLevel(hexSku)).hasValueSatisfying(l -> assertThat(l.onHand()).isEqualTo(1));
    assertThat(mongoTemplate.getCollection(StockLevelDocument.COLLECTION)
        .find(new Document("_id", hexSku)).first()).isNotNull();
  }

  /**
   * Many issues on the same SKU at once, more than the stock can serve. Conflicting transactions are retried a bounded
   * number of times, so some calls may still fail; whatever happens, stock must never go negative and must match the
   * ledger.
   */
  @Test
  void concurrentIssuesNeverMakeStockNegative() throws Exception {
    givenProduct(mongoTemplate, "SKU-C");
    recorded(operations.recordMovement(receipt("SKU-C", 10, "seed")));

    int calls = 20;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < calls; i++) {
      String key = "issue-" + i;
      Callable<Object> call = () -> {
        start.await();
        try {
          return operations.recordMovement(issue("SKU-C", 1, key));
        } catch (RuntimeException failure) {
          return failure;
        }
      };
      futures.add(pool.submit(call));
    }
    start.countDown();

    int recorded = 0;
    for (Future<Object> future : futures) {
      Object outcome = future.get(60, TimeUnit.SECONDS);
      if (outcome instanceof MovementResult.Recorded) {
        recorded++;
      } else if (outcome instanceof RuntimeException failure) {
        assertThat(TransactionRetry.isRetryable(failure))
            .as("only retryable conflicts may remain after the retries: %s", failure)
            .isTrue();
      } else {
        assertThat(outcome).isEqualTo(new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK));
      }
    }
    pool.shutdown();

    assertThat(recorded).isBetween(1, 10);
    assertThat(stockLevelDocument("SKU-C").onHand()).isEqualTo(10 - recorded);
    assertThat(movementCount("SKU-C")).isEqualTo(1 + recorded);
  }

  private static StockMovement recorded(MovementResult result) {
    assertThat(result).isInstanceOf(MovementResult.Recorded.class);
    MovementResult.Recorded recorded = (MovementResult.Recorded) result;
    assertThat(recorded.alreadyRecorded()).isFalse();
    return recorded.movement();
  }

  private StockLevelDocument stockLevelDocument(String sku) {
    StockLevelDocument level = mongoTemplate.findById(sku, StockLevelDocument.class);
    assertThat(level).as("stock level of %s", sku).isNotNull();
    return level;
  }

  private long movementCount(String sku) {
    return mongoTemplate.count(query(where("sku").is(sku)), StockMovementDocument.class);
  }
}
