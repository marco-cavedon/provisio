package io.github.marcocavedon.provisio.forecasting.internal;

import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code forecast_runs}: one document per batch run (ADR 0030).
 *
 * <p>Created when the run starts and updated by every attempt: a retried job increments {@code attempts} and resumes.
 * {@code ok} and {@code failed} are counted from {@code forecasts} when the run completes, never incremented along the
 * way, so a retry cannot count a product twice.
 *
 * @param runId the {@code _id}, derived from the Camunda element instance so every retry of a job finds the same run
 * @param asOf business date of the run
 * @param horizonEnd last forecast day, included
 * @param policy assumptions of the run (the default policy until supplier data exists)
 * @param processInstanceKey the Camunda process instance, to find the run in Operate
 * @param elementInstanceKey the Camunda element instance of the batch task
 * @param startedAt when the first attempt started
 * @param completedAt when the run completed, {@code null} while running
 * @param attempts attempts so far, 1 for a run that succeeded first time
 * @param status {@code RUNNING} or {@code COMPLETED}
 * @param requested products in the run
 * @param ok products with a forecast, set on completion
 * @param failed products without a forecast, set on completion
 */
@Document(collection = ForecastRunDocument.COLLECTION)
public record ForecastRunDocument(
    @MongoId(FieldType.STRING) String runId,
    LocalDate asOf,
    LocalDate horizonEnd,
    ReplenishmentPolicy policy,
    long processInstanceKey,
    long elementInstanceKey,
    Instant startedAt,
    Instant completedAt,
    int attempts,
    String status,
    int requested,
    Integer ok,
    Integer failed) {

  public static final String COLLECTION = "forecast_runs";
  static final String RUNNING = "RUNNING";
  static final String COMPLETED = "COMPLETED";
}
