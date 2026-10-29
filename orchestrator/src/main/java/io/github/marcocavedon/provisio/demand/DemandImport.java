package io.github.marcocavedon.provisio.demand;

import java.util.List;

/**
 * Write side of the demand module, used by the seed (ADR 0026).
 */
public interface DemandImport {

  /**
   * Replaces the whole demand history and the calendar, atomically.
   *
   * <p>Runs in a transaction, joining the caller's if there is one: the seed records what it loaded in the same
   * transaction. The input is validated before anything is written.
   *
   * @throws IllegalArgumentException if an entry is not strictly positive, falls on a day that is not open, or repeats
   *     a product and day
   */
  void replaceAll(BusinessCalendar calendar, List<DemandEntry> entries);
}
