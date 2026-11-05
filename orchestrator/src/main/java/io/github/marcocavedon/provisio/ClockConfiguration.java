package io.github.marcocavedon.provisio;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * System time for every module, injectable for tests.
 *
 * <p>It is wall-clock time, for timestamps such as {@code recordedAt} or {@code computedAt}. Business time is never
 * read from it: forecasting and replay take an explicit {@code asOf} date (ADR 0025).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
