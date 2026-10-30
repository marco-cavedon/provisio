package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Beans of the seed module. The loader exists only when {@code provisio.seed.dir} is set: tests and other contexts
 * start without a seed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeedProperties.class)
class SeedConfiguration {

  @Bean
  @ConditionalOnProperty(prefix = "provisio.seed", name = "dir")
  SeedLoader seedLoader(SeedProperties properties, MongoTemplate mongoTemplate, DemandImport demandImport,
      InventoryOperations inventory, PlatformTransactionManager transactionManager) {
    return new SeedLoader(properties.dir(), mongoTemplate, demandImport, inventory, transactionManager);
  }
}
