package io.github.marcocavedon.provisio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that multi-document transactions really work against MongoDB.
 *
 * <p>The rollback test is the one that matters: a commit-only test would also pass if transactions were silently
 * disabled (for example without a transaction manager), and the inventory ledger would lose its atomicity unnoticed.
 * Requires Docker.
 */
@SpringBootTest(properties = "camunda.client.worker.defaults.enabled=false")
@Import(MongoTestcontainersConfiguration.class)
class MongoTransactionTests {

  private static final String COLLECTION = "transaction_check";

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    // Collections cannot be created implicitly inside a transaction on every server version: create it up front
    if (mongoTemplate.collectionExists(COLLECTION)) {
      mongoTemplate.dropCollection(COLLECTION);
    }
    mongoTemplate.createCollection(COLLECTION);
    transaction = new TransactionTemplate(transactionManager);
  }

  @Test
  void commitsBothWrites() {
    transaction.executeWithoutResult(status -> {
      mongoTemplate.insert(new Document("step", 1), COLLECTION);
      mongoTemplate.insert(new Document("step", 2), COLLECTION);
    });

    assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments()).isEqualTo(2);
  }

  @Test
  void rollsBackEveryWriteWhenTheTransactionFails() {
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      mongoTemplate.insert(new Document("step", 1), COLLECTION);
      throw new IllegalStateException("simulated failure after the first write");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(mongoTemplate.getCollection(COLLECTION).countDocuments()).isZero();
  }
}
