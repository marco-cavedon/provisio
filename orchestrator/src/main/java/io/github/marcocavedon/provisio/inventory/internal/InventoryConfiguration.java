package io.github.marcocavedon.provisio.inventory.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans of the inventory module.
 */
@Configuration(proxyBeanMethods = false)
class InventoryConfiguration {

  /**
   * System time for {@code recordedAt} and {@code updatedAt}, injectable for tests. Moves to the shared configuration
   * when a second module needs a clock.
   */
  @Bean
  Clock inventoryClock() {
    return Clock.systemUTC();
  }
}
