/**
 * Time module: the business date, which is not the wall-clock date (ADR 0025, ADR 0031).
 *
 * <p>Other modules use only {@link io.github.marcocavedon.provisio.time.BusinessClock}. The implementation and its
 * configuration ({@code provisio.business-time.*}) are internal; spike 003 replaces the implementation, not the API.
 * The module depends on no other module.
 *
 * <p>A process reads the business date once, at its start, through the job worker
 * {@code provisio.time.get-business-date} in {@code time.worker}, and carries it as a variable. Workers that need a
 * business date take it as input and never read the clock themselves.
 */
package io.github.marcocavedon.provisio.time;
