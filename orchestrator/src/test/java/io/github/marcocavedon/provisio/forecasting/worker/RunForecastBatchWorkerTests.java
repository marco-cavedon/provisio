package io.github.marcocavedon.provisio.forecasting.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.client.exception.JobError;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.RunSummary;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The worker's own contract, case by case: business date, run identifier, output variable (ADR 0030). No Docker
 * needed; the invocation path is covered by {@link RunForecastBatchWorkerProcessTests}.
 */
class RunForecastBatchWorkerTests {

  @Test
  void parsesAnIsoBusinessDate() {
    assertThat(RunForecastBatchWorker.businessDate("2011-07-01")).isEqualTo(LocalDate.of(2011, 7, 1));
  }

  @Test
  void missingOrInvalidBusinessDateFailsTheJobWithoutRetries() {
    for (String asOf : new String[] {null, " ", "01/07/2011", "2011-02-30"}) {
      assertThatThrownBy(() -> RunForecastBatchWorker.businessDate(asOf))
          .as("asOf = %s", asOf)
          .isInstanceOfSatisfying(JobError.class, error -> assertThat(error.getRetries()).isZero());
    }
  }

  @Test
  void runIdentifierIsTheSameForEveryRetryOfTheJob() {
    assertThat(RunForecastBatchWorker.runId(2251799813685249L)).isEqualTo("batch-2251799813685249");
  }

  @Test
  void outputHasTheRunAndItsCounts() {
    Map<String, Object> result = RunForecastBatchWorker.result(
        new RunSummary("batch-1", LocalDate.of(2011, 7, 1), LocalDate.of(2011, 7, 28), 54, 50, 4));

    assertThat(result).containsExactly(
        Map.entry("runId", "batch-1"),
        Map.entry("asOf", "2011-07-01"),
        Map.entry("horizonEnd", "2011-07-28"),
        Map.entry("requested", 54),
        Map.entry("ok", 50),
        Map.entry("failed", 4));
  }
}
