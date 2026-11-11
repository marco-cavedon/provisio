package io.github.marcocavedon.provisio.forecasting.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.CompletedRun;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Job worker {@value #JOB_TYPE}: whether a business date already has a completed forecast run (ADR 0030).
 *
 * <p>Used on the timer path of the forecast batch process, which skips a date that already has one: the timer never
 * repeats a date, and a recompute is always explicit, through the message start event. Runs still {@code RUNNING} do
 * not count, and a completed run counts whatever its outcome (see {@link ForecastBatch#completedRun(LocalDate)}).
 *
 * <p>Input: {@code asOf}, an ISO-8601 date. Output: always the variable {@code completedRun}, so a gateway can test
 * {@code completedRun.found} without relying on how an undefined variable evaluates:
 *
 * <ul>
 *   <li>found: {@code {found: true, runId, asOf, horizonEnd, requested, ok, failed, completedAt}}, the most recently
 *       completed run of that date; the fields match {@code forecastRun}, the output of the batch;</li>
 *   <li>not found: {@code {found: false, asOf}}.</li>
 * </ul>
 *
 * <p>A missing or invalid {@code asOf} fails the job with no retries left: an incident at once, since it means the
 * process model does not set it. A database failure propagates and the engine retries. The class is {@code public}
 * because the Camunda starter invokes the method reflectively.
 */
@Component
public class FindCompletedRunWorker {

  static final String JOB_TYPE = "provisio.forecasting.find-completed-run";
  static final String RESULT_VARIABLE = "completedRun";

  private final ForecastBatch batch;

  public FindCompletedRunWorker(ForecastBatch batch) {
    this.batch = batch;
  }

  @JobWorker(type = JOB_TYPE)
  public Map<String, Object> findCompletedRun(@Variable(name = "asOf") String asOf) {
    LocalDate businessDate = RunForecastBatchWorker.businessDate(asOf);
    return Map.of(RESULT_VARIABLE, result(businessDate, batch.completedRun(businessDate)));
  }

  static Map<String, Object> result(LocalDate asOf, Optional<CompletedRun> completed) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (completed.isEmpty()) {
      result.put("found", false);
      result.put("asOf", asOf.toString());
      return result;
    }
    CompletedRun run = completed.get();
    result.put("found", true);
    result.put("runId", run.runId());
    result.put("asOf", run.asOf().toString());
    result.put("horizonEnd", run.horizonEnd().toString());
    result.put("requested", run.requested());
    result.put("ok", run.ok());
    result.put("failed", run.failed());
    result.put("completedAt", run.completedAt().toString());
    return result;
  }
}
