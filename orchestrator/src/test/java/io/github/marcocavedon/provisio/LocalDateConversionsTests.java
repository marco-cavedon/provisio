package io.github.marcocavedon.provisio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * A calendar day is midnight UTC whatever the JVM's default time zone (ADR 0026). No Docker needed.
 *
 * <p>The zones are the extremes: UTC+14 and UTC−8 (UTC−7 in summer). With Spring Data's default converters, both
 * would move the day.
 */
class LocalDateConversionsTests {

  private static final LocalDate DAY = LocalDate.of(2011, 3, 14);
  private static final Date MIDNIGHT_UTC = Date.from(Instant.parse("2011-03-14T00:00:00Z"));

  @Test
  void writesAndReadsMidnightUtcInAnyDefaultTimeZone() {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : new String[] {"Pacific/Kiritimati", "America/Los_Angeles", "Europe/Rome", "UTC"}) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        assertThat(LocalDateConversions.LocalDateToDate.INSTANCE.convert(DAY)).as(zone).isEqualTo(MIDNIGHT_UTC);
        assertThat(LocalDateConversions.DateToLocalDate.INSTANCE.convert(MIDNIGHT_UTC)).as(zone).isEqualTo(DAY);
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void refusesADateThatIsNotAtMidnightUtc() {
    // What the default converter writes for 14 March on a JVM in Europe/Rome
    Date romeMidnight = Date.from(Instant.parse("2011-03-13T23:00:00Z"));
    assertThatThrownBy(() -> LocalDateConversions.DateToLocalDate.INSTANCE.convert(romeMidnight))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("midnight UTC");
  }
}
