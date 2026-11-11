package io.github.marcocavedon.provisio.forecasting.internal;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandQueries;
import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.Product;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The forecast batch: every product, forecast as of one business date, stored as new versions (ADR 0006, ADR 0030).
 *
 * <p>A Zeebe job can be delivered more than once, after a failure or after a timeout while the first attempt is still
 * running. The batch is therefore idempotent and resumable rather than transactional:
 *
 * <ul>
 *   <li>the run identifier is derived from the job's element instance, the same for every retry of that job;</li>
 *   <li>each attempt computes only the products the run has not stored yet;</li>
 *   <li>a forecast's {@code _id} is {@code runId:sku}, so two concurrent attempts cannot store a product twice: the
 *       second insert is ignored, and the cost is computation, not duplicate data;</li>
 *   <li>the counts are taken from the stored forecasts when the run completes.</li>
 * </ul>
 *
 * <p>Chunks are not written in a transaction: each forecast is independent, and a partial run is a valid state to
 * resume from. Retrying is the engine's job, not this class's.
 */
@Service
public class ForecastBatch {

  private static final Logger log = LoggerFactory.getLogger(ForecastBatch.class);

  private final DemandQueries demand;
  private final InventoryQueries inventory;
  private final DemandForecaster forecaster;
  private final MongoTemplate mongoTemplate;
  private final ForecastingProperties properties;
  private final Clock clock;

  ForecastBatch(DemandQueries demand, InventoryQueries inventory, DemandForecaster forecaster,
      MongoTemplate mongoTemplate, ForecastingProperties properties, Clock clock) {
    this.demand = demand;
    this.inventory = inventory;
    this.forecaster = forecaster;
    this.mongoTemplate = mongoTemplate;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * One attempt at a run: the first, or a retry that resumes it.
   *
   * @throws ForecastingRejectedException if the run cannot succeed: no history, {@code asOf} outside the observed
   *     calendar, a calendar that does not cover the horizon, or a request the service rejects
   * @throws ForecastingUnavailableException if the service is unavailable; the products stored so far stay stored
   */
  public RunSummary run(RunRequest request) {
    Objects.requireNonNull(request, "request");
    BusinessCalendar calendar = demand.calendar()
        .orElseThrow(() -> new ForecastingRejectedException("No demand history loaded: the seed has not run"));
    LocalDate asOf = request.asOf();
    LocalDate horizonEnd = asOf.plusDays(properties.horizonDays() - 1L);
    checkDates(calendar, asOf, horizonEnd);
    ReplenishmentPolicy policy = properties.defaultPolicy().toPolicy();
    List<String> skus = inventory.products().stream().map(Product::sku).toList();

    int attempt = startAttempt(request, horizonEnd, policy, skus.size());
    Set<String> stored = new HashSet<>(mongoTemplate.findDistinct(query(where("runId").is(request.runId())), "sku",
        ForecastDocument.class, String.class));
    List<String> pending = skus.stream().filter(sku -> !stored.contains(sku)).toList();
    log.info("Forecast run {} as of {}, attempt {}: {} of {} products to compute", request.runId(), asOf, attempt,
        pending.size(), skus.size());

    int chunkSize = properties.chunkSize();
    for (int from = 0; from < pending.size(); from += chunkSize) {
      List<String> chunk = pending.subList(from, Math.min(from + chunkSize, pending.size()));
      List<DemandForecaster.Series> series = chunk.stream()
          .map(sku -> new DemandForecaster.Series(sku, demand.dailyDemand(sku, calendar.from(), asOf), policy))
          .toList();
      String requestId = request.runId() + ":attempt-" + attempt + ":chunk-" + (from / chunkSize);
      DemandForecaster.Results results =
          forecaster.forecast(new DemandForecaster.Call(requestId, asOf, horizonEnd, calendar, series));
      store(request.runId(), asOf, horizonEnd, policy, results);
    }
    return complete(request.runId(), asOf, horizonEnd, skus.size());
  }

  /**
   * The most recent completed run for a business date, if any: the timer path of the forecast batch process skips a
   * date that already has one, so a recompute is always explicit, through the message start event.
   *
   * <p>A completed run counts whatever its outcome, even with every product {@code FAILED}; runs still {@code RUNNING}
   * do not, so a run interrupted for good never blocks the timer. Checking and then starting a run is not atomic:
   * acceptable, because the timer is the only caller on that path and a duplicate run costs computation, not data.
   */
  public Optional<CompletedRun> completedRun(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    // Served by the asOf_status_completedAt index: equality on asOf and status, then the sort key
    var latest = query(where("asOf").is(asOf).and("status").is(ForecastRunDocument.COMPLETED))
        .with(Sort.by(Direction.DESC, "completedAt"))
        .limit(1);
    return Optional.ofNullable(mongoTemplate.findOne(latest, ForecastRunDocument.class))
        .map(run -> new CompletedRun(run.runId(), run.asOf(), run.horizonEnd(), run.requested(), run.ok(), run.failed(),
            run.completedAt()));
  }

  private static void checkDates(BusinessCalendar calendar, LocalDate asOf, LocalDate horizonEnd) {
    // The history is every open day before asOf, and it must be observed: asOf can be at most the day after the last
    // observed day (ADR 0029)
    if (asOf.isAfter(calendar.observedTo().plusDays(1))) {
      throw new ForecastingRejectedException("asOf " + asOf + " is more than one day after the last observed day "
          + calendar.observedTo() + ": the demand before it is not known");
    }
    if (calendar.openDays(calendar.from(), asOf).findAny().isEmpty()) {
      throw new ForecastingRejectedException("No open day before asOf " + asOf + " in the calendar starting on "
          + calendar.from() + ": there is no history to forecast from");
    }
    if (horizonEnd.isAfter(calendar.to())) {
      throw new ForecastingRejectedException("The horizon ends on " + horizonEnd + ", after the calendar ends on "
          + calendar.to() + ": extend the planned calendar (ADR 0029) or shorten provisio.forecasting.horizon-days");
    }
  }

  /** Records the attempt; returns its number, 1 for the first. */
  private int startAttempt(RunRequest request, LocalDate horizonEnd, ReplenishmentPolicy policy, int requested) {
    try {
      mongoTemplate.insert(new ForecastRunDocument(request.runId(), request.asOf(), horizonEnd, policy,
          request.processInstanceKey(), request.elementInstanceKey(), now(), null, 1, ForecastRunDocument.RUNNING,
          requested, null, null));
      return 1;
    } catch (DuplicateKeyException retry) {
      // A retry of the same job: same run, same business date. Anything else is a bug, and the run is left untouched.
      ForecastRunDocument existing = mongoTemplate.findById(request.runId(), ForecastRunDocument.class);
      if (existing == null || !existing.asOf().equals(request.asOf())) {
        throw new ForecastingRejectedException("Run " + request.runId() + " exists with asOf "
            + (existing == null ? "unknown" : existing.asOf()) + ", not " + request.asOf());
      }
      ForecastRunDocument before = mongoTemplate.findAndModify(query(where("_id").is(request.runId())),
          new Update().inc("attempts", 1).set("status", ForecastRunDocument.RUNNING), ForecastRunDocument.class);
      // findAndModify returns the document before the update
      return (before == null ? existing.attempts() : before.attempts()) + 1;
    }
  }

  private void store(String runId, LocalDate asOf, LocalDate horizonEnd, ReplenishmentPolicy policy,
      DemandForecaster.Results results) {
    Instant computedAt = now();
    for (DemandForecaster.Result result : results.results()) {
      Forecast forecast = new Forecast(ForecastDocument.id(runId, result.sku()), result.sku(), runId, asOf, horizonEnd,
          computedAt, policy, result.outcome(), result.demandClass(), result.classification(), result.method(),
          result.parameters(), result.granularity(), result.periods(), result.protectionInterval(), result.failure(),
          results.service());
      try {
        mongoTemplate.insert(ForecastDocument.from(forecast));
      } catch (DuplicateKeyException concurrent) {
        // Stored by another attempt of the same run (a job that timed out while still running): keep the first
        log.info("Forecast {} already stored by another attempt", forecast.id());
      }
    }
  }

  private RunSummary complete(String runId, LocalDate asOf, LocalDate horizonEnd, int requested) {
    int ok = (int) mongoTemplate.count(query(where("runId").is(runId).and("outcome").is(Forecast.Outcome.OK.name())),
        ForecastDocument.class);
    int failed = (int) mongoTemplate.count(
        query(where("runId").is(runId).and("outcome").is(Forecast.Outcome.FAILED.name())), ForecastDocument.class);
    mongoTemplate.updateFirst(query(where("_id").is(runId)), new Update()
        .set("status", ForecastRunDocument.COMPLETED)
        .set("completedAt", now())
        .set("ok", ok)
        .set("failed", failed), ForecastRunDocument.class);
    log.info("Forecast run {} completed: {} forecast, {} failed of {}", runId, ok, failed, requested);
    return new RunSummary(runId, asOf, horizonEnd, requested, ok, failed);
  }

  private Instant now() {
    // MongoDB stores milliseconds: truncate, so what is returned equals what is read back
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * One attempt of a run, as the job worker sees it.
   *
   * @param runId stable across retries of the job
   * @param asOf business date
   * @param processInstanceKey Camunda process instance, recorded for Operate
   * @param elementInstanceKey Camunda element instance of the batch task
   */
  public record RunRequest(String runId, LocalDate asOf, long processInstanceKey, long elementInstanceKey) {

    public RunRequest {
      Objects.requireNonNull(runId, "runId");
      Objects.requireNonNull(asOf, "asOf");
    }
  }

  /**
   * What a completed run produced.
   *
   * @param runId the run
   * @param asOf business date
   * @param horizonEnd last forecast day, included
   * @param requested products in the run
   * @param ok products with a forecast
   * @param failed products without one
   */
  public record RunSummary(String runId, LocalDate asOf, LocalDate horizonEnd, int requested, int ok, int failed) {
  }

  /**
   * A run that completed, as {@link #completedRun(LocalDate)} finds it.
   *
   * @param runId the run
   * @param asOf business date
   * @param horizonEnd last forecast day, included
   * @param requested products in the run
   * @param ok products with a forecast
   * @param failed products without one
   * @param completedAt when it completed
   */
  public record CompletedRun(String runId, LocalDate asOf, LocalDate horizonEnd, int requested, int ok, int failed,
      Instant completedAt) {
  }
}
