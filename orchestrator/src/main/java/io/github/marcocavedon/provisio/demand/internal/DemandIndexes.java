package io.github.marcocavedon.provisio.demand.internal;

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
 * Creates the demand collections and indexes at startup, idempotently (ADR 0026), with the same rules as the
 * inventory module: explicit names, and a conflicting existing index stops the application.
 *
 * <p>Collections are created up front because the history is replaced inside a transaction, where creating a
 * collection implicitly is not reliable.
 */
@Component
class DemandIndexes implements InitializingBean {

  static final String BUCKETS_BY_SKU_AND_MONTH = "sku_month_unique";

  private static final Logger log = LoggerFactory.getLogger(DemandIndexes.class);

  private final MongoTemplate mongoTemplate;

  DemandIndexes(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    createIndexes();
  }

  void createIndexes() {
    List.of(DemandBucketDocument.COLLECTION, CalendarYearDocument.COLLECTION).forEach(name -> {
      if (!mongoTemplate.collectionExists(name)) {
        mongoTemplate.createCollection(name);
      }
    });

    try {
      // One bucket per product and month; serves every series read
      mongoTemplate.getCollection(DemandBucketDocument.COLLECTION).createIndex(
          Indexes.ascending("sku", "month"),
          new IndexOptions().name(BUCKETS_BY_SKU_AND_MONTH).unique(true));
    } catch (MongoServerException conflict) {
      throw new IllegalStateException(
          "Cannot create the demand indexes: an existing index conflicts with the expected definition. "
              + "Inspect it with getIndexes() and drop it if it is wrong (ADR 0026). Cause: "
              + conflict.getMessage(), conflict);
    }
    log.info("Demand collections and indexes are in place");
  }
}
