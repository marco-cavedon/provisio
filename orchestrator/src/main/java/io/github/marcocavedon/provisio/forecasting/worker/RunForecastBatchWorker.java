package io.github.marcocavedon.provisio.forecasting.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.exception.CamundaError;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.RunRequest;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.RunSummary;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastingRejectedException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Job worker {@value #JOB_TYPE}: the forecast batch for one business date (ADR 0006, ADR 0025, ADR 0030).
 *
 * <p>Input: {@code asOf}, an ISO-8601 date, set by the process (from the business clock on the timer path, by the
 * caller on the message path). Output: {@code forecastRun}, {@code {runId, asOf, horizonEnd, requested, ok, failed}}.
 * Products the service could not forecast are a business outcome, counted in {@code failed}: the job completes, and
 * the process decides what to do with them.
 *
 * <p>Failures follow the tool error contract of the orchestrator README:
 *
 * <ul>
 *   <li>service unavailable (connection, timeout, 5xx): the exception propagates, the engine retries after
 *       {@code retryBackoff}, and the next attempt resumes the same run;</li>
 *   <li>a run that cannot succeed (missing or invalid {@code asOf}, a calendar that does not cover the horizon, a
 *       request the service rejects): the job fails with no retries left, so an incident shows the reason at once.</li>
 * </ul>
 *
 * <p>The run identifier comes from the element instance key, which every retry of this job shares. The timeout
 * covers a whole run; if it expires while the run is still going, a second attempt resumes it and the stored forecasts
 * cannot be duplicated (see {@link ForecastBatch}).
 */
@Component
public class RunForecastBatchWorker {

  static final String JOB_TYPE = "provisio.forecasting.run-batch";
  static final String RESULT_VARIABLE = "forecastRun";

  private final ForecastBatch batch;

  public RunForecastBatchWorker(ForecastBatch batch) {
    this.batch = batch;
  }

  @JobWorker(type = JOB_TYPE, timeout = 600_000L, retryBackoff = 30_000L, maxJobsActive = 1)
  public Map<String, Object> runBatch(ActivatedJob job, @Variable(name = "asOf") String asOf) {
    RunSummary summary;
    try {
      summary = batch.run(new RunRequest(runId(job.getElementInstanceKey()), businessDate(asOf),
          job.getProcessInstanceKey(), job.getElementInstanceKey()));
    } catch (ForecastingRejectedException cannotSucceed) {
      throw CamundaError.jobError(cannotSucceed.getMessage(), null, 0);
    }
    return Map.of(RESULT_VARIABLE, result(summary));
  }

  static String runId(long elementInstanceKey) {
    return "batch-" + elementInstanceKey;
  }

  static LocalDate businessDate(String asOf) {
    if (asOf == null || asOf.isBlank()) {
      throw CamundaError.jobError("Variable asOf is missing: the forecast batch needs a business date (ADR 0025)",
          null, 0);
    }
    try {
      return LocalDate.parse(asOf);
    } catch (DateTimeParseException invalid) {
      throw CamundaError.jobError("Variable asOf is not an ISO-8601 date (yyyy-MM-dd): " + asOf, null, 0);
    }
  }

  static Map<String, Object> result(RunSummary summary) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", summary.runId());
    result.put("asOf", summary.asOf().toString());
    result.put("horizonEnd", summary.horizonEnd().toString());
    result.put("requested", summary.requested());
    result.put("ok", summary.ok());
    result.put("failed", summary.failed());
    return result;
  }
}
