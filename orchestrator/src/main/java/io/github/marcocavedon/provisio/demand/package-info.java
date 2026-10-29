/**
 * Demand module: daily demand history per product and the store's opening calendar (ADR 0025, ADR 0026).
 *
 * <p>History is stored in monthly buckets per product, because every read is a series. Other modules use only the
 * types in this package:
 *
 * <ul>
 *   <li>{@link io.github.marcocavedon.provisio.demand.DemandQueries} to read the calendar and zero-filled
 *       series;</li>
 *   <li>{@link io.github.marcocavedon.provisio.demand.DemandImport} to replace the whole history, used by the seed.</li>
 * </ul>
 *
 * <p>The module records sales as an estimate of demand: it knows nothing of products, stock or forecasts.
 */
package io.github.marcocavedon.provisio.demand;
