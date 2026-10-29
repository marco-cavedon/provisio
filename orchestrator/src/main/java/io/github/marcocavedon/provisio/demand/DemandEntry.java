package io.github.marcocavedon.provisio.demand;

import java.time.LocalDate;

/**
 * Demand of one product on one day with sales, as imported.
 *
 * @param sku opaque product identifier
 * @param day an open day of the calendar it is imported with
 * @param quantity units sold, strictly positive: days without sales have no entry
 */
public record DemandEntry(String sku, LocalDate day, int quantity) {
}
