package io.github.marcocavedon.provisio.time.internal;

import io.github.marcocavedon.provisio.time.BusinessClock;
import io.github.marcocavedon.provisio.time.internal.BusinessTimeProperties.Mapped;
import java.time.Clock;
import java.time.LocalDate;

/**
 * The business clock until spike 003 (ADR 0031): a fixed date, or the current date moved into the data years.
 *
 * <p>The mapped mode reads the wall-clock date from the injected {@link Clock}, which is UTC: the same time zone as
 * engine timers, so a timer at 01:00 UTC and this clock agree on the day. It is not monotonic: once a year it jumps
 * back from {@code latest} to the day after it, a year earlier. Harmless while only the forecast batch reads it.
 */
class ConfiguredBusinessClock implements BusinessClock {

  private final BusinessTimeProperties properties;
  private final Clock clock;

  ConfiguredBusinessClock(BusinessTimeProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public LocalDate today() {
    return switch (properties.mode()) {
      case FIXED -> properties.fixedDate();
      case MAPPED -> mapped(LocalDate.now(clock), properties.mapped());
    };
  }

  /**
   * {@code date} with its year replaced by {@code rule.year()}, or by the year before if the result falls after
   * {@code rule.latest()}. 29 February becomes 28 February, as {@link LocalDate#withYear} does.
   */
  static LocalDate mapped(LocalDate date, Mapped rule) {
    LocalDate candidate = date.withYear(rule.year());
    return candidate.isAfter(rule.latest()) ? date.withYear(rule.year() - 1) : candidate;
  }
}
