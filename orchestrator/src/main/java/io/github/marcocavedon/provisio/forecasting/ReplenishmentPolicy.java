package io.github.marcocavedon.provisio.forecasting;

import java.util.Objects;

/**
 * The assumptions a forecast is computed with (ADR 0028, ADR 0030).
 *
 * <p>The demand quantile covers the protection interval, which depends on these values, so they are stored with every
 * forecast: without them a forecast cannot be interpreted, and computing it again with another lead time gives a new
 * version, not a correction.
 *
 * @param leadTime supplier lead time, an estimate that can be wrong
 * @param reviewPeriodDays calendar days between two replenishment reviews
 * @param serviceLevel probability of not running out over the protection interval, strictly between 0 and 1
 */
public record ReplenishmentPolicy(LeadTime leadTime, int reviewPeriodDays, double serviceLevel) {

  public ReplenishmentPolicy {
    Objects.requireNonNull(leadTime, "leadTime");
    if (reviewPeriodDays < 1 || reviewPeriodDays > 365) {
      throw new IllegalArgumentException("Review period must be 1 to 365 days: " + reviewPeriodDays);
    }
    if (!(serviceLevel > 0 && serviceLevel < 1)) {
      throw new IllegalArgumentException("Service level must be strictly between 0 and 1: " + serviceLevel);
    }
  }

  /**
   * Length in calendar days of the protection interval starting on the business date, as the contract defines it:
   * the lead time rounded up, plus the review period.
   */
  public int protectionIntervalDays() {
    return (int) Math.ceil(leadTime.meanDays()) + reviewPeriodDays;
  }

  /**
   * Supplier lead time in calendar days.
   *
   * @param meanDays expected lead time, more than 0 and at most 365
   * @param stdDays standard deviation, or {@code null} when the lead time is treated as known
   */
  public record LeadTime(double meanDays, Double stdDays) {

    public LeadTime {
      if (!(meanDays > 0 && meanDays <= 365)) {
        throw new IllegalArgumentException("Mean lead time must be more than 0 and at most 365 days: " + meanDays);
      }
      if (stdDays != null && !(stdDays >= 0 && stdDays <= 365)) {
        throw new IllegalArgumentException("Lead time standard deviation must be 0 to 365 days: " + stdDays);
      }
    }
  }
}
