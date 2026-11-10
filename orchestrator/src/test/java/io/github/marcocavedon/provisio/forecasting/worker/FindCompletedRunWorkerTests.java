package io.github.marcocavedon.provisio.forecasting.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.camunda.client.exception.JobError;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.CompletedRun;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The output of {@code provisio.forecasting.find-completed-run}, case by case (ADR 0030). No Docker needed; the query
 * is covered by {@code ForecastBatchTests}, the invocation path by {@link FindCompletedRunWorkerProcessTests}.
 */
class FindCompletedRunWorkerTests {

  private static final LocalDate AS_OF = LocalDate.of(2011, 7, 1);

  @Test
  void foundRunHasTheFieldsOfTheBatchOutputAndItsCompletionTime() {
    CompletedRun run = new CompletedRun("batch-7", AS_OF, LocalDate.of(2011, 7, 28), 54, 0, 54,
        Instant.parse("2026-11-10T01:00:12.345Z"));

    assertThat(FindCompletedRunWorker.result(AS_OF, Optional.of(run))).containsExactly(
        Map.entry("found", true),
        Map.entry("runId", "batch-7"),
        Map.entry("asOf", "2011-07-01"),
        Map.entry("horizonEnd", "2011-07-28"),
        Map.entry("requested", 54),
        Map.entry("ok", 0),
        Map.entry("failed", 54),
        Map.entry("completedAt", "2026-11-10T01:00:12.345Z"));
  }

  @Test
  void noRunIsNotFoundWithTheDateThatWasChecked() {
    assertThat(FindCompletedRunWorker.result(AS_OF, Optional.empty())).containsExactly(
        Map.entry("found", false),
        Map.entry("asOf", "2011-07-01"));
  }

  @Test
  void missingOrInvalidBusinessDateFailsTheJobWithoutRetriesOrQueries() {
    ForecastBatch batch = mock(ForecastBatch.class);
    FindCompletedRunWorker worker = new FindCompletedRunWorker(batch);

    for (String asOf : new String[] {null, "", "2011-13-01"}) {
      assertThatThrownBy(() -> worker.findCompletedRun(asOf))
          .as("asOf = %s", asOf)
          .isInstanceOfSatisfying(JobError.class, error -> assertThat(error.getRetries()).isZero());
    }
    verifyNoInteractions(batch);
  }
}
