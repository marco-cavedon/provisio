package io.github.marcocavedon.provisio.forecasting.internal;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What the batch needs from the forecasting service, in the module's own types (ADR 0030).
 *
 * <p>{@link ForecastingClient} implements it over the generated client; tests implement it in memory. The generated
 * types never leave {@code ForecastingClient}, so a change of generator or of its options stays there.
 */
interface DemandForecaster {

  /**
   * Forecasts one chunk of products.
   *
   * @return one result per product, in the order of {@code call.series()}
   * @throws ForecastingUnavailableException if the service cannot be reached or fails: worth retrying
   * @throws ForecastingRejectedException if the service rejects the request or answers outside the contract: a bug,
   *     not worth retrying
   */
  Results forecast(Call call);

  /**
   * One request: shared calendar and dates, one series per product.
   *
   * @param requestId correlates the call with the run, e.g. {@code runId:chunk-0}
   * @param asOf business date; every history entry is strictly earlier
   * @param horizonEnd last forecast day, included
   * @param calendar the calendar to send, from its first day to {@code horizonEnd}
   * @param series the products of this chunk
   */
  record Call(String requestId, LocalDate asOf, LocalDate horizonEnd, BusinessCalendar calendar, List<Series> series) {
  }

  /**
   * @param sku the product
   * @param history every open day before {@code asOf}, zeros included
   * @param policy the assumptions to compute with
   */
  record Series(String sku, List<DailyDemand> history, ReplenishmentPolicy policy) {
  }

  /**
   * @param service what computed the results
   * @param results one per product, in request order
   */
  record Results(Forecast.Service service, List<Result> results) {
  }

  /**
   * The part of a {@link Forecast} the service computes; the batch adds identity, dates and policy.
   */
  record Result(String sku, Forecast.Outcome outcome, String demandClass, Forecast.Classification classification,
      String method, Map<String, Object> parameters, String granularity, List<Forecast.Period> periods,
      Forecast.ProtectionInterval protectionInterval, Forecast.Failure failure) {
  }
}
