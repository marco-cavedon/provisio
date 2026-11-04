package io.github.marcocavedon.provisio.forecasting;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Read side of the forecasting module.
 */
public interface ForecastQueries {

  /** A forecast version by its identifier ({@code runId:sku}). */
  Optional<Forecast> findForecast(String id);

  /**
   * The most recent forecast version of a product made on or before a business date: the latest {@code asOf}, and among
   * versions with the same {@code asOf} the latest computed. Failed versions are included: the caller sees why there is
   * no number rather than an older one presented as current.
   */
  Optional<Forecast> latestForecast(String sku, LocalDate asOf);
}
