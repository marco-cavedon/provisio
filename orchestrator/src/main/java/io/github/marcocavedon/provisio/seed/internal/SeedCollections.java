package io.github.marcocavedon.provisio.seed.internal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates {@code seed_loads} at startup, idempotently, whether or not a seed is configured.
 *
 * <p>The loader writes it inside a transaction, where a collection cannot be listed and creating one implicitly is not
 * reliable, so it must exist beforehand.
 */
@Component
class SeedCollections implements InitializingBean {

  private final MongoTemplate mongoTemplate;

  SeedCollections(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    if (!mongoTemplate.collectionExists(SeedLoadDocument.COLLECTION)) {
      mongoTemplate.createCollection(SeedLoadDocument.COLLECTION);
    }
  }
}
