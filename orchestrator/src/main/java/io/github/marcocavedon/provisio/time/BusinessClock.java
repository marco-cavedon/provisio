package io.github.marcocavedon.provisio.time;

import java.time.LocalDate;

/**
 * The current business date (ADR 0031).
 *
 * <p>Business time is the date the demo store lives in, inside the sales data, not the wall-clock date. It is read
 * once per process instance and carried as a variable: a long-running process must not see two business dates.
 */
public interface BusinessClock {

  /** Today's business date. */
  LocalDate today();
}
