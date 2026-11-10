package io.github.marcocavedon.provisio.time.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.marcocavedon.provisio.time.internal.BusinessTimeProperties.Mapped;
import io.github.marcocavedon.provisio.time.internal.BusinessTimeProperties.Mode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The two modes of the business clock (ADR 0031), on a fixed wall clock. No Docker needed.
 */
class ConfiguredBusinessClockTests {

  private static final Mapped RULE = new Mapped(2011, LocalDate.of(2011, 12, 9));

  @Test
  void mapsTheCurrentDateIntoTheDataYear() {
    assertThat(mappedToday("2026-11-10T02:00:00Z")).isEqualTo(LocalDate.of(2011, 11, 10));
    assertThat(mappedToday("2027-01-01T01:00:00Z")).isEqualTo(LocalDate.of(2011, 1, 1));
  }

  @Test
  void theLatestDayIsStillInTheDataYear() {
    assertThat(mappedToday("2026-12-09T01:00:00Z")).isEqualTo(LocalDate.of(2011, 12, 9));
  }

  @Test
  void daysAfterTheLatestFallBackToTheYearBefore() {
    assertThat(mappedToday("2026-12-10T01:00:00Z")).isEqualTo(LocalDate.of(2010, 12, 10));
    assertThat(mappedToday("2026-12-31T01:00:00Z")).isEqualTo(LocalDate.of(2010, 12, 31));
  }

  @Test
  void theTwentyNinthOfFebruaryBecomesTheTwentyEighth() {
    assertThat(mappedToday("2028-02-29T01:00:00Z")).isEqualTo(LocalDate.of(2011, 2, 28));
    assertThat(mappedToday("2028-02-28T01:00:00Z")).isEqualTo(LocalDate.of(2011, 2, 28));
  }

  @Test
  void theDayIsTheUtcDayLikeEngineTimers() {
    // Already 10 December in Italy, still 9 December in UTC
    assertThat(mappedToday("2026-12-09T23:30:00Z")).isEqualTo(LocalDate.of(2011, 12, 9));
  }

  @Test
  void theFixedModeIgnoresTheWallClock() {
    BusinessTimeProperties fixed = new BusinessTimeProperties(Mode.FIXED, LocalDate.of(2011, 12, 5), RULE);

    assertThat(new ConfiguredBusinessClock(fixed, clockAt("2026-11-10T02:00:00Z")).today())
        .isEqualTo(LocalDate.of(2011, 12, 5));
    assertThat(new ConfiguredBusinessClock(fixed, clockAt("2027-03-01T02:00:00Z")).today())
        .isEqualTo(LocalDate.of(2011, 12, 5));
  }

  private static LocalDate mappedToday(String instant) {
    BusinessTimeProperties mapped = new BusinessTimeProperties(Mode.MAPPED, null, RULE);
    return new ConfiguredBusinessClock(mapped, clockAt(instant)).today();
  }

  private static Clock clockAt(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }
}
