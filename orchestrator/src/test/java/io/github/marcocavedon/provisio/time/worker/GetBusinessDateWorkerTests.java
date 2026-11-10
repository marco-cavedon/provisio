package io.github.marcocavedon.provisio.time.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The output of {@code provisio.time.get-business-date} (ADR 0031). No Docker needed; the invocation path is covered
 * by {@link GetBusinessDateWorkerProcessTests}.
 */
class GetBusinessDateWorkerTests {

  @Test
  void returnsTheBusinessDateAsAnIsoDate() {
    GetBusinessDateWorker worker = new GetBusinessDateWorker(() -> LocalDate.of(2011, 7, 1));

    assertThat(worker.getBusinessDate()).isEqualTo(Map.of("businessDate", "2011-07-01"));
  }
}
