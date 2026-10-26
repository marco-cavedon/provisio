package io.github.marcocavedon.provisio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Shared MongoDB infrastructure, used by every module.
 *
 * <p>Spring Data MongoDB does not register a transaction manager on its own: without this bean,
 * {@code @Transactional} is silently ignored and multi-document writes are not atomic. The write path of the
 * inventory ledger depends on it (ADR 0022). Transactions require a replica set, which the local stack provides
 * (ADR 0007).
 */
@Configuration(proxyBeanMethods = false)
class MongoConfiguration {

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
  }
}
