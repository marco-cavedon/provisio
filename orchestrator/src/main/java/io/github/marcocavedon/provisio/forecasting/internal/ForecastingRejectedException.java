package io.github.marcocavedon.provisio.forecasting.internal;

/**
 * A batch that cannot succeed by retrying: the service rejected the request (4xx) or answered outside the contract, or
 * the batch's own preconditions do not hold (no {@code asOf}, a calendar that does not cover the horizon). The job
 * fails with no retries left, so an incident shows the reason at once.
 */
public class ForecastingRejectedException extends RuntimeException {

  public ForecastingRejectedException(String message) {
    super(message);
  }

  public ForecastingRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
