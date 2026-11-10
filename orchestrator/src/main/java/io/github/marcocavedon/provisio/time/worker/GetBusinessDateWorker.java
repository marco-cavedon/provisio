package io.github.marcocavedon.provisio.time.worker;

import io.camunda.client.annotation.JobWorker;
import io.github.marcocavedon.provisio.time.BusinessClock;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Job worker {@value #JOB_TYPE}: today's business date (ADR 0031, ADR 0019).
 *
 * <p>No input. Output: {@code businessDate}, an ISO-8601 date ({@code yyyy-MM-dd}). The variable is named after what
 * it is, not after its consumer: a process maps it to its own variable (the forecast batch to {@code asOf}). A process
 * calls it once, at its start, and carries the date from there.
 *
 * <p>It has no failure of its own: an invalid configuration stops the application at startup. The class is
 * {@code public} because the Camunda starter invokes the method reflectively.
 */
@Component
public class GetBusinessDateWorker {

  static final String JOB_TYPE = "provisio.time.get-business-date";
  static final String RESULT_VARIABLE = "businessDate";

  private final BusinessClock clock;

  public GetBusinessDateWorker(BusinessClock clock) {
    this.clock = clock;
  }

  @JobWorker(type = JOB_TYPE)
  public Map<String, Object> getBusinessDate() {
    return Map.of(RESULT_VARIABLE, clock.today().toString());
  }
}
