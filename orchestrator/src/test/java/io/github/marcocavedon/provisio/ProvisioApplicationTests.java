package io.github.marcocavedon.provisio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context assembles, including the Camunda client.
 *
 * <p>It verifies wiring and dependency alignment, not infrastructure: job workers are disabled so no broker is
 * needed. It would have caught the HttpClient version conflict found in spike 001.
 */
@SpringBootTest(properties = "camunda.client.worker.defaults.enabled=false")
class ProvisioApplicationTests {

  @Test
  void contextLoads() {
  }
}
