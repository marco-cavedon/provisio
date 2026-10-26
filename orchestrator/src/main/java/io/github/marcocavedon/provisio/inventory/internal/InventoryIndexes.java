package io.github.marcocavedon.provisio.inventory.internal;

import com.mongodb.MongoServerException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the inventory collections and indexes at startup, idempotently (ADR 0022).
 *
 * <p>Spring Data's automatic index creation is disabled: the definitions here are the reviewed, explicit source.
 * Every index has an explicit name. If an index with the same name or keys already exists with different options
 * (for example {@code idempotencyKey} without {@code unique}), MongoDB refuses to create it and startup fails: the
 * idempotency guarantee must never disappear silently.
 *
 * <p>Collections are created up front because creating one implicitly inside a transaction is not reliable on every
 * server version, and {@code stock_levels} has no secondary index that would create it.
 */
@Component
class InventoryIndexes implements InitializingBean {

  static final String MOVEMENTS_BY_SKU_AND_TIME = "sku_occurredAt";
  static final String MOVEMENTS_BY_IDEMPOTENCY_KEY = "idempotencyKey_unique";
  static final String PRODUCTS_BY_CATEGORY = "category";

  private static final Logger log = LoggerFactory.getLogger(InventoryIndexes.class);

  private final MongoTemplate mongoTemplate;

  InventoryIndexes(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    createIndexes();
  }

  void createIndexes() {
    List.of(ProductDocument.COLLECTION, StockMovementDocument.COLLECTION, StockLevelDocument.COLLECTION)
        .forEach(this::createCollectionIfMissing);

    try {
      // Per-SKU history and replay
      mongoTemplate.getCollection(StockMovementDocument.COLLECTION).createIndex(
          Indexes.ascending("sku", "occurredAt"),
          new IndexOptions().name(MOVEMENTS_BY_SKU_AND_TIME));
      // A retried job must not record the same movement twice
      mongoTemplate.getCollection(StockMovementDocument.COLLECTION).createIndex(
          Indexes.ascending("idempotencyKey"),
          new IndexOptions().name(MOVEMENTS_BY_IDEMPOTENCY_KEY).unique(true));
      mongoTemplate.getCollection(ProductDocument.COLLECTION).createIndex(
          Indexes.ascending("category"),
          new IndexOptions().name(PRODUCTS_BY_CATEGORY));
    } catch (MongoServerException conflict) {
      throw new IllegalStateException(
          "Cannot create the inventory indexes: an existing index conflicts with the expected definition. "
              + "Inspect it with getIndexes() and drop it if it is wrong (ADR 0022). Cause: "
              + conflict.getMessage(), conflict);
    }
    log.info("Inventory collections and indexes are in place");
  }

  private void createCollectionIfMissing(String name) {
    if (!mongoTemplate.collectionExists(name)) {
      mongoTemplate.createCollection(name);
    }
  }
}
