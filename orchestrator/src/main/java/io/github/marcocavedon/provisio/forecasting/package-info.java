/**
 * Forecasting module: the forecast batch and the versioned forecasts it stores (ADR 0006, ADR 0028, ADR 0030).
 *
 * <p>The numbers come from the stateless forecasting service (Python), called through a client generated from
 * {@code contracts/forecasting/openapi.yaml}. This module owns what the service must not: choosing the products, reading
 * their history from the demand module, the replenishment policy, persistence and idempotency. Other modules use only
 * the types in this package:
 *
 * <ul>
 *   <li>{@link io.github.marcocavedon.provisio.forecasting.ForecastQueries} to read a forecast by id, or the latest
 *       one of a product as of a business date;</li>
 *   <li>{@link io.github.marcocavedon.provisio.forecasting.Forecast} and
 *       {@link io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy}, what a forecast is and the assumptions
 *       it was computed with.</li>
 * </ul>
 *
 * <p>The batch is the job worker {@code provisio.forecasting.run-batch} in {@code forecasting.worker}. The generated
 * client, documents and services are internal. A forecast is never changed once stored: a new computation is a new
 * version.
 */
package io.github.marcocavedon.provisio.forecasting;
