package io.github.marcocavedon.provisio.forecasting.internal;

import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code forecasts}: one version per product and run, appended and never updated (ADR 0030).
 *
 * <p>The {@code _id} is the natural key {@code runId:sku}. It makes the batch idempotent: a retried or concurrent run
 * that computes a product again cannot store it twice, because the second insert hits the same {@code _id}. It is also
 * the identifier a purchase-order proposal refers to. {@code @MongoId(STRING)} keeps it a string.
 *
 * <p>Nested values reuse the public records of {@link Forecast}; calendar days go through the {@code LocalDate}
 * converters (midnight UTC, ADR 0026). Absent fields of a failed forecast are not written.
 */
@Document(collection = ForecastDocument.COLLECTION)
public record ForecastDocument(
    @MongoId(FieldType.STRING) String id,
    String sku,
    String runId,
    LocalDate asOf,
    LocalDate horizonEnd,
    Instant computedAt,
    ReplenishmentPolicy policy,
    String outcome,
    String demandClass,
    Forecast.Classification classification,
    String method,
    Map<String, Object> parameters,
    String granularity,
    List<Forecast.Period> periods,
    Forecast.ProtectionInterval protectionInterval,
    Forecast.Failure failure,
    Forecast.Service service) {

  public static final String COLLECTION = "forecasts";

  static String id(String runId, String sku) {
    return runId + ":" + sku;
  }

  static ForecastDocument from(Forecast forecast) {
    return new ForecastDocument(forecast.id(), forecast.sku(), forecast.runId(), forecast.asOf(),
        forecast.horizonEnd(), forecast.computedAt(), forecast.policy(), forecast.outcome().name(),
        forecast.demandClass(), forecast.classification(), forecast.method(), forecast.parameters(),
        forecast.granularity(), forecast.periods(), forecast.protectionInterval(), forecast.failure(),
        forecast.service());
  }

  Forecast toForecast() {
    return new Forecast(id, sku, runId, asOf, horizonEnd, computedAt, policy, Forecast.Outcome.valueOf(outcome),
        demandClass, classification, method, parameters, granularity, periods, protectionInterval, failure, service);
  }
}
