package io.github.marcocavedon.provisio.inventory.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Collections and indexes created at startup (ADR 0022). Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class InventoryIndexesTests {

  @Autowired
  private InventoryIndexes indexes;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Test
  void createsTheCollectionsAndIndexesAtStartup() {
    assertThat(mongoTemplate.collectionExists(StockLevelDocument.COLLECTION)).isTrue();

    Document byIdempotencyKey = index(StockMovementDocument.COLLECTION, InventoryIndexes.MOVEMENTS_BY_IDEMPOTENCY_KEY);
    assertThat(byIdempotencyKey.get("key", Document.class)).isEqualTo(new Document("idempotencyKey", 1));
    assertThat(byIdempotencyKey.getBoolean("unique")).isTrue();

    Document bySkuAndTime = index(StockMovementDocument.COLLECTION, InventoryIndexes.MOVEMENTS_BY_SKU_AND_TIME);
    assertThat(bySkuAndTime.get("key", Document.class))
        .isEqualTo(new Document("sku", 1).append("occurredAt", 1));

    Document byCategory = index(ProductDocument.COLLECTION, InventoryIndexes.PRODUCTS_BY_CATEGORY);
    assertThat(byCategory.get("key", Document.class)).isEqualTo(new Document("category", 1));
  }

  @Test
  void creationIsIdempotent() {
    assertThatCode(indexes::createIndexes).doesNotThrowAnyException();
  }

  @Test
  void conflictingIndexFailsInsteadOfBeingIgnored() {
    MongoCollection<Document> movements = mongoTemplate.getCollection(StockMovementDocument.COLLECTION);
    movements.dropIndex(InventoryIndexes.MOVEMENTS_BY_IDEMPOTENCY_KEY);
    // Same name and keys, but not unique: the idempotency guarantee would be gone
    movements.createIndex(Indexes.ascending("idempotencyKey"),
        new IndexOptions().name(InventoryIndexes.MOVEMENTS_BY_IDEMPOTENCY_KEY));
    try {
      assertThatThrownBy(indexes::createIndexes)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("conflicts");
    } finally {
      movements.dropIndex(InventoryIndexes.MOVEMENTS_BY_IDEMPOTENCY_KEY);
      indexes.createIndexes();
    }
  }

  private Document index(String collection, String name) {
    List<Document> all = mongoTemplate.getCollection(collection).listIndexes().into(new ArrayList<>());
    return all.stream()
        .filter(index -> name.equals(index.getString("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("index " + name + " not found on " + collection + ": " + all));
  }
}
