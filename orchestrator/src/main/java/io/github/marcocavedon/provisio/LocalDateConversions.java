package io.github.marcocavedon.provisio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * A calendar day is stored as a BSON {@code Date} at midnight UTC (ADR 0026).
 *
 * <p>Spring Data's default converters for local temporal types use the JVM's default time zone: on a machine in
 * Europe/Rome, 14 March would be stored as 13 March 23:00 UTC, and read back as a different day by a JVM in another
 * zone. These converters ignore the default time zone in both directions.
 */
final class LocalDateConversions {

  private LocalDateConversions() {
  }

  @WritingConverter
  enum LocalDateToDate implements Converter<LocalDate, Date> {
    INSTANCE;

    @Override
    public Date convert(LocalDate source) {
      return Date.from(source.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
  }

  /**
   * Fails on a {@code Date} that is not at midnight UTC: it was not written as a day, and truncating it would silently
   * move it to another day.
   */
  @ReadingConverter
  enum DateToLocalDate implements Converter<Date, LocalDate> {
    INSTANCE;

    @Override
    public LocalDate convert(Date source) {
      Instant instant = source.toInstant();
      LocalDate day = LocalDate.ofInstant(instant, ZoneOffset.UTC);
      if (!day.atStartOfDay(ZoneOffset.UTC).toInstant().equals(instant)) {
        throw new IllegalArgumentException(
            "Expected a calendar day stored at midnight UTC, found " + instant + " (ADR 0026)");
      }
      return day;
    }
  }
}
