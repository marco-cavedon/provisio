package io.github.marcocavedon.provisio.forecasting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One forecast version of one product, as computed by one batch run (ADR 0006, ADR 0030). Never changed once stored.
 *
 * <p>With outcome {@code OK} every field but {@code failure} is present; with {@code FAILED} only {@code failure} is
 * guaranteed, and the product has no forecast in that run. Both are stored: "no forecast, and why" is part of the
 * audit trail.
 *
 * @param id stable identifier, {@code runId:sku}; a purchase-order proposal refers to it
 * @param sku the product
 * @param runId the batch run that computed it
 * @param asOf business date the forecast is made on; the history used is strictly earlier
 * @param horizonEnd last day of the forecast, included
 * @param computedAt wall-clock time it was stored, for staleness
 * @param policy the assumptions it was computed with
 * @param outcome whether a forecast was produced
 * @param demandClass Syntetos–Boylan class of the history, as reported by the service
 * @param classification the figures behind the class
 * @param method forecasting method applied, as reported by the service
 * @param parameters fitted parameters and choices of the method, as reported by the service
 * @param granularity length of the forecast periods, {@code DAILY} or {@code WEEKLY}
 * @param periods forecast periods from {@code asOf} to {@code horizonEnd}, in date order
 * @param protectionInterval demand over the protection interval, mean and quantile
 * @param failure why no forecast was produced
 * @param service what computed it: service version and library versions
 */
public record Forecast(
    String id,
    String sku,
    String runId,
    LocalDate asOf,
    LocalDate horizonEnd,
    Instant computedAt,
    ReplenishmentPolicy policy,
    Outcome outcome,
    String demandClass,
    Classification classification,
    String method,
    Map<String, Object> parameters,
    String granularity,
    List<Period> periods,
    ProtectionInterval protectionInterval,
    Failure failure,
    Service service) {

  /** Whether the service produced a forecast for the product. */
  public enum Outcome { OK, FAILED }

  /**
   * @param adi average demand interval, in open days
   * @param cv2 squared coefficient of variation of the non-zero demand sizes
   */
  public record Classification(double adi, double cv2) {
  }

  /**
   * @param start first day of the period
   * @param end last day of the period, included
   * @param mean expected demand over the open days of the period
   */
  public record Period(LocalDate start, LocalDate end, double mean) {
  }

  /**
   * Demand over the days a replenishment decision taken on {@code asOf} must cover.
   *
   * @param start first day, {@code asOf}
   * @param end last day, included
   * @param openDays open days in the interval
   * @param mean expected demand
   * @param quantile demand not exceeded with probability {@code serviceLevel}
   * @param serviceLevel the service level the quantile is for
   * @param quantileMethod how the quantile was estimated
   */
  public record ProtectionInterval(LocalDate start, LocalDate end, int openDays, double mean, double quantile,
      double serviceLevel, String quantileMethod) {
  }

  /**
   * @param code machine-readable reason, an open set: unknown codes are possible
   * @param message human-readable reason
   */
  public record Failure(String code, String message) {
  }

  /**
   * @param version forecasting service version
   * @param libraries versions of the libraries that determine the numbers, by package name
   */
  public record Service(String version, Map<String, String> libraries) {
  }
}
