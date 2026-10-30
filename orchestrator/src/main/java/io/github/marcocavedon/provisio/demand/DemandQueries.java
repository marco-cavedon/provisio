package io.github.marcocavedon.provisio.demand;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the demand module.
 */
public interface DemandQueries {

  /** The opening calendar, empty if no demand history has been loaded. */
  Optional<BusinessCalendar> calendar();

  /**
   * Daily demand of one product: one entry per open day from {@code from} included to {@code endExclusive} excluded,
   * zero where nothing was sold; closed days are omitted. This is the only place where zeros are filled in.
   *
   * <p>A product without history gets a series of zeros: whether it exists is the inventory module's concern.
   *
   * @throws IllegalArgumentException if the range is reversed or not covered by the calendar
   * @throws IllegalStateException if no demand history has been loaded
   */
  List<DailyDemand> dailyDemand(String sku, LocalDate from, LocalDate endExclusive);
}
