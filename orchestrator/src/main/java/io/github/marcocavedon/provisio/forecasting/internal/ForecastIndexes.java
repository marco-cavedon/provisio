package io.github.marcocavedon.provisio.forecasting.internal;

import com.mongodb.MongoServerException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the forecasting collections and indexes at startup, idempotently (ADR 0022, ADR 0030).
 *
 * <p>Same rules as the other modules: explicit names, no automatic index creation, and an index that exists with other
 * options stops startup.
 */
@Component
class ForecastIndexes implements InitializingBean {

  /** The latest forecast of a product as of a business date: equality on sku, then the two sort keys. */
  static final String FORECASTS_BY_SKU_AS_OF_AND_TIME = "sku_asOf_computedAt";
  /** Products already stored by a run, read when a retried job resumes. */
  static final String FORECASTS_BY_RUN = "runId_sku";

  private final MongoTemplate mongoTemplate;

  ForecastIndexes(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    createIndexes();
  }

  void createIndexes() {
    List.of(ForecastDocument.COLLECTION, ForecastRunDocument.COLLECTION).forEach(name -> {
      if (!mongoTemplate.collectionExists(name)) {
        mongoTemplate.createCollection(name);
      }
    });
    try {
      mongoTemplate.getCollection(ForecastDocument.COLLECTION).createIndex(
          Indexes.compoundIndex(Indexes.ascending("sku"), Indexes.descending("asOf", "computedAt")),
          new IndexOptions().name(FORECASTS_BY_SKU_AS_OF_AND_TIME));
      mongoTemplate.getCollection(ForecastDocument.COLLECTION).createIndex(
          Indexes.ascending("runId", "sku"),
          new IndexOptions().name(FORECASTS_BY_RUN));
    } catch (MongoServerException conflict) {
      throw new IllegalStateException("Cannot create the forecasting indexes: an index with the same name or keys "
          + "exists with different options. Drop it deliberately and restart.", conflict);
    }
  }
}
