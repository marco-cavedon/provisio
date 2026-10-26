package io.github.marcocavedon.provisio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Smoke test: the application context assembles, including the Camunda client.
 *
 * <p>It verifies wiring and dependency alignment: job workers are disabled so no broker is needed. MongoDB comes from
 * a container, because the inventory module creates its collections and indexes at startup (ADR 0022). Same
 * properties and imports as the other integration tests, so Spring reuses one context and one container.
 * It would have caught the HttpClient version conflict found in spike 001. Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class ProvisioApplicationTests {

  @Test
  void contextLoads() {
  }
}
