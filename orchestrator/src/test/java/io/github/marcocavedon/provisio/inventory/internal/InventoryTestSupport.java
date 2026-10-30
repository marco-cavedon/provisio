package io.github.marcocavedon.provisio.inventory.internal;

import io.github.marcocavedon.provisio.inventory.MovementType;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Fixtures shared by the inventory integration tests.
 *
 * <p>Products are inserted directly, so fixtures do not depend on {@code registerProducts}, which has its own tests.
 */
final class InventoryTestSupport {

  static final Instant OCCURRED_AT = Instant.parse("2026-10-01T10:00:00Z");

  private InventoryTestSupport() {
  }

  /** Removes every document but keeps collections and indexes, which are created once at startup. */
  static void clear(MongoTemplate mongoTemplate) {
    mongoTemplate.remove(new Query(), ProductDocument.class);
    mongoTemplate.remove(new Query(), StockMovementDocument.class);
    mongoTemplate.remove(new Query(), StockLevelDocument.class);
  }

  static void givenProduct(MongoTemplate mongoTemplate, String sku) {
    mongoTemplate.insert(new ProductDocument(sku, "Product " + sku, "FOODS", "EA"));
  }

  static RecordMovement receipt(String sku, int quantity, String key) {
    return new RecordMovement(sku, MovementType.RECEIPT, quantity, OCCURRED_AT, key, null, null);
  }

  static RecordMovement issue(String sku, int quantity, String key) {
    return new RecordMovement(sku, MovementType.ISSUE, -quantity, OCCURRED_AT, key, null, null);
  }

  static RecordMovement adjustment(String sku, int delta, String key) {
    return new RecordMovement(sku, MovementType.ADJUSTMENT, delta, OCCURRED_AT, key, null, "cycle count");
  }
}
