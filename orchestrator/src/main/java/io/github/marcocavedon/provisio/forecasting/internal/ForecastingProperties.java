package io.github.marcocavedon.provisio.forecasting.internal;

import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy.LeadTime;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code provisio.forecasting.*}, defaults in {@code application.yaml} (ADR 0030). Invalid values stop the application
 * at startup.
 *
 * @param baseUrl the forecasting service, e.g. {@code http://localhost:8000}
 * @param connectTimeout time allowed to open a connection
 * @param readTimeout time allowed for one chunk to be computed
 * @param chunkSize products per request, 1 to 100 (the contract's limit)
 * @param horizonDays forecast days from {@code asOf}, {@code asOf} included; at least the default protection interval
 * @param defaultPolicy assumptions used until supplier data provides them (v0.3.0)
 */
@ConfigurationProperties("provisio.forecasting")
public record ForecastingProperties(
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int chunkSize,
    int horizonDays,
    DefaultPolicy defaultPolicy) {

  /** The contract's {@code maxItems} on {@code series}. */
  static final int MAX_CHUNK_SIZE = 100;

  public ForecastingProperties {
    Objects.requireNonNull(baseUrl, "provisio.forecasting.base-url");
    Objects.requireNonNull(connectTimeout, "provisio.forecasting.connect-timeout");
    Objects.requireNonNull(readTimeout, "provisio.forecasting.read-timeout");
    Objects.requireNonNull(defaultPolicy, "provisio.forecasting.default-policy");
    if (chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE) {
      throw new IllegalArgumentException("provisio.forecasting.chunk-size must be 1 to " + MAX_CHUNK_SIZE + ": "
          + chunkSize);
    }
    int protection = defaultPolicy.toPolicy().protectionIntervalDays();
    if (horizonDays < protection) {
      throw new IllegalArgumentException("provisio.forecasting.horizon-days (" + horizonDays + ") is shorter than the "
          + "protection interval of the default policy (" + protection + " days): the service would reject every request");
    }
  }

  /**
   * The default replenishment policy, flat so it reads well in YAML.
   *
   * @param leadTimeMeanDays expected supplier lead time
   * @param leadTimeStdDays its standard deviation, absent when the lead time is treated as known
   * @param reviewPeriodDays days between two reviews
   * @param serviceLevel cycle service level
   */
  public record DefaultPolicy(double leadTimeMeanDays, Double leadTimeStdDays, int reviewPeriodDays,
      double serviceLevel) {

    ReplenishmentPolicy toPolicy() {
      return new ReplenishmentPolicy(new LeadTime(leadTimeMeanDays, leadTimeStdDays), reviewPeriodDays, serviceLevel);
    }
  }
}
