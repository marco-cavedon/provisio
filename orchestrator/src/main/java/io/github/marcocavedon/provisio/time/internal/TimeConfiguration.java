package io.github.marcocavedon.provisio.time.internal;

import io.github.marcocavedon.provisio.time.BusinessClock;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans of the time module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessTimeProperties.class)
class TimeConfiguration {

  /** The business clock, on the shared wall clock of {@code ClockConfiguration}. */
  @Bean
  BusinessClock businessClock(BusinessTimeProperties properties, Clock clock) {
    return new ConfiguredBusinessClock(properties, clock);
  }
}
