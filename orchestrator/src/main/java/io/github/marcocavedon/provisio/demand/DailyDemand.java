package io.github.marcocavedon.provisio.demand;

import java.time.LocalDate;

/**
 * Demand of one product on one open day; zero if nothing was sold.
 *
 * @param day an open day
 * @param quantity units sold, never negative
 */
public record DailyDemand(LocalDate day, int quantity) {
}
