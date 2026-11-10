package io.github.marcocavedon.provisio.time.internal;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

/**
 * {@code provisio.business-time.*}, defaults in {@code application.yaml} (ADR 0031). Invalid values stop the
 * application at startup.
 *
 * @param mode {@code mapped} or {@code fixed}
 * @param fixedDate the business date in the fixed mode; must be absent in the mapped mode, where it would be ignored
 * @param mapped the rule of the mapped mode
 */
@ConfigurationProperties("provisio.business-time")
public record BusinessTimeProperties(
    Mode mode,
    @DateTimeFormat(iso = ISO.DATE) LocalDate fixedDate,
    Mapped mapped) {

  /** How the business date is obtained. */
  public enum Mode {
    /** The current date moved into the data years. */
    MAPPED,
    /** Always {@code fixedDate}. */
    FIXED
  }

  public BusinessTimeProperties {
    Objects.requireNonNull(mode, "provisio.business-time.mode");
    Objects.requireNonNull(mapped, "provisio.business-time.mapped");
    if (mode == Mode.FIXED && fixedDate == null) {
      throw new IllegalArgumentException(
          "provisio.business-time.fixed-date is required when provisio.business-time.mode is fixed");
    }
    if (mode == Mode.MAPPED && fixedDate != null) {
      throw new IllegalArgumentException("provisio.business-time.fixed-date (" + fixedDate + ") would be ignored: "
          + "set provisio.business-time.mode to fixed, or remove the date");
    }
  }

  /**
   * The rule of the mapped mode: the current date with its year replaced by {@code year}, or by the year before if the
   * result falls after {@code latest}.
   *
   * @param year the year the current date is moved into
   * @param latest the last business date the mapped mode may return, in {@code year}
   */
  public record Mapped(int year, @DateTimeFormat(iso = ISO.DATE) LocalDate latest) {

    public Mapped {
      Objects.requireNonNull(latest, "provisio.business-time.mapped.latest");
      if (latest.getYear() != year) {
        throw new IllegalArgumentException("provisio.business-time.mapped.latest (" + latest + ") is not in "
            + "provisio.business-time.mapped.year (" + year + ")");
      }
    }
  }
}
