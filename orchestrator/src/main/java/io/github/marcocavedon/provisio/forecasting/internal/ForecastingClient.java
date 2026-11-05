package io.github.marcocavedon.provisio.forecasting.internal;

import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.forecasting.internal.client.ApiClient;
import io.github.marcocavedon.provisio.forecasting.internal.client.api.DefaultApi;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.ForecastRequest;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.ForecastResponse;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.LeadTime;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.Observation;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.SeriesInput;
import io.github.marcocavedon.provisio.forecasting.internal.client.model.SeriesResult;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link DemandForecaster} over the client generated from {@code contracts/forecasting/openapi.yaml} (ADR 0030).
 *
 * <p>The only class that sees the generated types: it maps the module's types to the request and the response back,
 * and turns HTTP failures into the two outcomes the batch acts on. Transient failures (connection refused, timeout,
 * 5xx) are worth retrying; a 4xx, or a response that does not fit the contract, is a bug on one side or the other and
 * would fail again.
 */
class ForecastingClient implements DemandForecaster {

  /** Problem details can list up to 100 violations: enough of the body to diagnose, not a wall of text. */
  private static final int MAX_BODY_IN_MESSAGE = 2_000;

  private final DefaultApi api;

  ForecastingClient(RestClient restClient, URI baseUrl) {
    ApiClient apiClient = new ApiClient(restClient);
    apiClient.setBasePath(baseUrl.toString().replaceAll("/+$", ""));
    this.api = new DefaultApi(apiClient);
  }

  @Override
  public Results forecast(Call call) {
    ForecastResponse response;
    try {
      response = api.computeForecasts(toRequest(call));
    } catch (RestClientResponseException failed) {
      String detail = "forecasting service answered " + failed.getStatusCode().value() + " to " + call.requestId()
          + ": " + truncate(failed.getResponseBodyAsString());
      if (failed.getStatusCode().is5xxServerError()) {
        throw new ForecastingUnavailableException(detail, failed);
      }
      throw new ForecastingRejectedException(detail, failed);
    } catch (ResourceAccessException unreachable) {
      throw new ForecastingUnavailableException("forecasting service unreachable for " + call.requestId() + ": "
          + unreachable.getMessage(), unreachable);
    } catch (RestClientException unreadable) {
      // For example an enum value the contract does not define: the two sides disagree on the contract
      throw new ForecastingRejectedException("forecasting service response to " + call.requestId()
          + " does not fit the contract: " + unreadable.getMessage(), unreadable);
    }
    return toResults(call, response);
  }

  static ForecastRequest toRequest(Call call) {
    var calendar = new io.github.marcocavedon.provisio.forecasting.internal.client.model.BusinessCalendar()
        .from(call.calendar().from())
        .to(call.horizonEnd());
    call.calendar().closedDays().stream()
        .filter(day -> !day.isAfter(call.horizonEnd()))
        .sorted()
        .forEach(calendar::addClosedDaysItem);

    ForecastRequest request = new ForecastRequest()
        .requestId(call.requestId())
        .asOf(call.asOf())
        .horizonEnd(call.horizonEnd())
        .calendar(calendar);
    for (Series series : call.series()) {
      request.addSeriesItem(toSeries(series));
    }
    return request;
  }

  private static SeriesInput toSeries(Series series) {
    ReplenishmentPolicy policy = series.policy();
    LeadTime leadTime = new LeadTime().meanDays(BigDecimal.valueOf(policy.leadTime().meanDays()));
    if (policy.leadTime().stdDays() != null) {
      leadTime.stdDays(BigDecimal.valueOf(policy.leadTime().stdDays()));
    }
    SeriesInput input = new SeriesInput()
        .sku(series.sku())
        .leadTime(leadTime)
        .reviewPeriodDays(policy.reviewPeriodDays())
        .serviceLevel(BigDecimal.valueOf(policy.serviceLevel()));
    for (DailyDemand day : series.history()) {
      input.addObservationsItem(new Observation().date(day.day()).quantity(BigDecimal.valueOf(day.quantity())));
    }
    return input;
  }

  static Results toResults(Call call, ForecastResponse response) {
    List<SeriesResult> results = response.getResults() == null ? List.of() : response.getResults();
    List<String> expected = call.series().stream().map(Series::sku).toList();
    List<String> received = results.stream().map(SeriesResult::getSku).toList();
    if (!received.equals(expected)) {
      throw new ForecastingRejectedException("forecasting service answered " + call.requestId() + " for products "
          + received + ", expected " + expected + " in this order");
    }
    var service = response.getService();
    Forecast.Service info = new Forecast.Service(service.getVersion(),
        service.getLibraries() == null ? Map.of() : Map.copyOf(service.getLibraries()));
    return new Results(info, results.stream().map(ForecastingClient::toResult).toList());
  }

  private static Result toResult(SeriesResult result) {
    String sku = result.getSku();
    if (result.getOutcome() != SeriesResult.OutcomeEnum.OK) {
      var error = result.getError();
      Forecast.Failure failure = error == null
          ? new Forecast.Failure("UNKNOWN", "the service gave no reason")
          : new Forecast.Failure(error.getCode(), error.getMessage());
      return new Result(sku, Forecast.Outcome.FAILED, null, null, null, null, null, null, null, failure);
    }
    // With outcome OK the contract guarantees every field but error: a missing one is the service's bug
    var classification = required(result.getClassification(), "classification", sku);
    var interval = required(result.getProtectionInterval(), "protectionInterval", sku);
    List<Forecast.Period> periods = new ArrayList<>();
    for (var point : required(result.getForecast(), "forecast", sku)) {
      periods.add(new Forecast.Period(point.getPeriodStart(), point.getPeriodEnd(),
          number(point.getMean(), "forecast.mean", sku)));
    }
    return new Result(
        sku,
        Forecast.Outcome.OK,
        required(result.getDemandClass(), "demandClass", sku).getValue(),
        new Forecast.Classification(number(classification.getAdi(), "classification.adi", sku),
            number(classification.getCv2(), "classification.cv2", sku)),
        required(result.getMethod(), "method", sku),
        required(result.getParameters(), "parameters", sku),
        required(result.getGranularity(), "granularity", sku).getValue(),
        List.copyOf(periods),
        new Forecast.ProtectionInterval(
            interval.getStart(), interval.getEnd(), required(interval.getOpenDays(), "protectionInterval.openDays", sku),
            number(interval.getMean(), "protectionInterval.mean", sku),
            number(interval.getQuantile(), "protectionInterval.quantile", sku),
            number(interval.getServiceLevel(), "protectionInterval.serviceLevel", sku),
            interval.getQuantileMethod()),
        null);
  }

  private static <T> T required(T value, String field, String sku) {
    if (value == null) {
      throw new ForecastingRejectedException("forecasting service returned outcome OK for " + sku + " without "
          + field + ", which the contract requires");
    }
    return value;
  }

  private static double number(BigDecimal value, String field, String sku) {
    return required(value, field, sku).doubleValue();
  }

  private static String truncate(String body) {
    return body.length() <= MAX_BODY_IN_MESSAGE ? body : body.substring(0, MAX_BODY_IN_MESSAGE) + "...";
  }
}
