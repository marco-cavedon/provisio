package io.github.marcocavedon.provisio.forecasting.internal;

/**
 * The forecasting service could not be reached, timed out or failed (5xx). Transient: the engine retries the job, and
 * the batch resumes from the products already stored.
 */
public class ForecastingUnavailableException extends RuntimeException {

  public ForecastingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
