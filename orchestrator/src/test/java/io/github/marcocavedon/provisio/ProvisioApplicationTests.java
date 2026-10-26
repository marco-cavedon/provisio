package io.github.marcocavedon.provisio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context assembles, including the Camunda client.
 *
 * <p>It verifies wiring and dependency alignment, not infrastructure: job workers are disabled so no broker is
 * needed, and the MongoDB driver connects lazily so no database is needed either (the password is a placeholder).
 * It would have caught the HttpClient version conflict found in spike 001.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-no-connection-is-made"
})
class ProvisioApplicationTests {

  @Test
  void contextLoads() {
  }
}
