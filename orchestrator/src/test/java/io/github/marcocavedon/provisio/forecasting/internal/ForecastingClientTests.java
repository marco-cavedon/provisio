package io.github.marcocavedon.provisio.forecasting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy.LeadTime;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The forecasting client against a mocked HTTP server: the request as the contract defines it, the response mapped
 * to the module's types, and HTTP failures turned into retry or no retry (ADR 0030). No Docker needed.
 *
 * <p>The real service is called by {@code RunForecastBatchWorkerProcessTests}; its placeholder only answers FAILED,
 * so the OK mapping is tested here.
 */
class ForecastingClientTests {

  private static final String URL = "http://forecasting:8000/v1/forecasts";
  private static final ReplenishmentPolicy POLICY = new ReplenishmentPolicy(new LeadTime(10.5, 2.5), 7, 0.95);

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  // A trailing slash in the configured URL must not produce a double slash
  private final ForecastingClient client = new ForecastingClient(builder.build(), URI.create("http://forecasting:8000/"));

  @Test
  void sendsTheRequestTheContractDefines() {
    server.expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        // TEMPORARY: prints the request body to diagnose closedDays in CI
        .andExpect(request -> System.out.println("REQUEST-BODY "
            + ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
        .andExpect(jsonPath("$.requestId").value("batch-1:attempt-1:chunk-0"))
        .andExpect(jsonPath("$.asOf").value("2011-07-01"))
        .andExpect(jsonPath("$.horizonEnd").value("2011-07-28"))
        .andExpect(jsonPath("$.calendar.from").value("2011-06-01"))
        // Sent up to the horizon only: the closed day after it is left out
        .andExpect(jsonPath("$.calendar.to").value("2011-07-28"))
        .andExpect(jsonPath("$.calendar.closedDays").value(List.of("2011-06-04", "2011-07-02")))
        .andExpect(jsonPath("$.series[0].sku").value("A"))
        .andExpect(jsonPath("$.series[0].observations[0].date").value("2011-06-01"))
        .andExpect(jsonPath("$.series[0].observations[0].quantity").value(3))
        .andExpect(jsonPath("$.series[0].observations[1].quantity").value(0))
        .andExpect(jsonPath("$.series[0].leadTime.meanDays").value(10.5))
        .andExpect(jsonPath("$.series[0].leadTime.stdDays").value(2.5))
        .andExpect(jsonPath("$.series[0].reviewPeriodDays").value(7))
        .andExpect(jsonPath("$.series[0].serviceLevel").value(0.95))
        .andRespond(withSuccess(response(failed("A")), MediaType.APPLICATION_JSON));

    client.forecast(call("A"));

    server.verify();
  }

  @Test
  void mapsAForecast() {
    server.expect(requestTo(URL)).andRespond(withSuccess(response("""
        {
          "sku": "A", "outcome": "OK", "demandClass": "LUMPY", "classification": {"adi": 2.5, "cv2": 0.8},
          "method": "TSB", "parameters": {"alphaD": 0.1, "alphaP": 0.2}, "granularity": "DAILY",
          "forecast": [{"periodStart": "2011-07-01", "periodEnd": "2011-07-01", "mean": 1.25}],
          "protectionInterval": {"start": "2011-07-01", "end": "2011-07-18", "openDays": 15, "mean": 18.75,
            "quantile": 40.0, "serviceLevel": 0.95, "quantileMethod": "BOOTSTRAP"}
        }"""), MediaType.APPLICATION_JSON));

    DemandForecaster.Results results = client.forecast(call("A"));

    assertThat(results.service()).isEqualTo(new Forecast.Service("0.1.0", Map.of("statsforecast", "2.1.1")));
    DemandForecaster.Result result = results.results().getFirst();
    assertThat(result.outcome()).isEqualTo(Forecast.Outcome.OK);
    assertThat(result.demandClass()).isEqualTo("LUMPY");
    assertThat(result.classification()).isEqualTo(new Forecast.Classification(2.5, 0.8));
    assertThat(result.method()).isEqualTo("TSB");
    assertThat(result.parameters()).containsEntry("alphaD", 0.1).containsEntry("alphaP", 0.2);
    assertThat(result.granularity()).isEqualTo("DAILY");
    assertThat(result.periods())
        .containsExactly(new Forecast.Period(LocalDate.of(2011, 7, 1), LocalDate.of(2011, 7, 1), 1.25));
    assertThat(result.protectionInterval()).isEqualTo(new Forecast.ProtectionInterval(LocalDate.of(2011, 7, 1),
        LocalDate.of(2011, 7, 18), 15, 18.75, 40.0, 0.95, "BOOTSTRAP"));
    assertThat(result.failure()).isNull();
  }

  @Test
  void mapsAFailedProduct() {
    server.expect(requestTo(URL)).andRespond(withSuccess(response(failed("A")), MediaType.APPLICATION_JSON));

    DemandForecaster.Result result = client.forecast(call("A")).results().getFirst();

    assertThat(result.outcome()).isEqualTo(Forecast.Outcome.FAILED);
    assertThat(result.failure()).isEqualTo(new Forecast.Failure("METHOD_NOT_IMPLEMENTED", "not yet"));
    assertThat(result.periods()).isNull();
  }

  @Test
  void rejectedRequestIsNotRetried() {
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(422))
        .contentType(MediaType.valueOf("application/problem+json"))
        .body("""
            {"type": "about:blank", "title": "The request breaks the forecasting contract", "status": 422,
             "errors": [{"pointer": "/horizonEnd", "code": "HORIZON_NOT_AFTER_AS_OF", "message": "..."}]}"""));

    assertThatThrownBy(() -> client.forecast(call("A")))
        .isInstanceOf(ForecastingRejectedException.class)
        .hasMessageContaining("422")
        .hasMessageContaining("HORIZON_NOT_AFTER_AS_OF");
  }

  @Test
  void serverErrorIsRetried() {
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> client.forecast(call("A")))
        .isInstanceOf(ForecastingUnavailableException.class).hasMessageContaining("503");
  }

  @Test
  void unreachableServiceIsRetried() {
    server.expect(requestTo(URL)).andRespond(withException(new IOException("Connection refused")));

    assertThatThrownBy(() -> client.forecast(call("A")))
        .isInstanceOf(ForecastingUnavailableException.class).hasMessageContaining("unreachable");
  }

  @Test
  void valueOutsideTheContractIsNotRetried() {
    server.expect(requestTo(URL)).andRespond(withSuccess(response("""
        {"sku": "A", "outcome": "OK", "demandClass": "SEASONAL"}"""), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.forecast(call("A")))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("does not fit the contract");
  }

  @Test
  void forecastWithoutARequiredFieldIsNotRetried() {
    server.expect(requestTo(URL)).andRespond(withSuccess(response("""
        {"sku": "A", "outcome": "OK", "demandClass": "LUMPY", "classification": {"adi": 2.5, "cv2": 0.8},
         "method": "TSB", "parameters": {}, "granularity": "DAILY", "forecast": []}"""), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.forecast(call("A")))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("protectionInterval");
  }

  @Test
  void resultsForOtherProductsAreNotAccepted() {
    server.expect(requestTo(URL)).andRespond(withSuccess(response(failed("B") + ", " + failed("A")),
        MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.forecast(call("A", "B")))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("in this order");
  }

  private static DemandForecaster.Call call(String... skus) {
    // Observed in June 2011, planned in July; closed on 4 June, 2 July and 30 July
    BusinessCalendar calendar = new BusinessCalendar(LocalDate.of(2011, 6, 1), LocalDate.of(2011, 6, 30),
        LocalDate.of(2011, 7, 31), Set.of(LocalDate.of(2011, 6, 4), LocalDate.of(2011, 7, 2), LocalDate.of(2011, 7, 30)));
    List<DailyDemand> history = List.of(new DailyDemand(LocalDate.of(2011, 6, 1), 3),
        new DailyDemand(LocalDate.of(2011, 6, 2), 0));
    List<DemandForecaster.Series> series = Arrays.stream(skus)
        .map(sku -> new DemandForecaster.Series(sku, history, POLICY))
        .toList();
    return new DemandForecaster.Call("batch-1:attempt-1:chunk-0", LocalDate.of(2011, 7, 1), LocalDate.of(2011, 7, 28),
        calendar, series);
  }

  private static String failed(String sku) {
    return """
        {"sku": "%s", "outcome": "FAILED", "error": {"code": "METHOD_NOT_IMPLEMENTED", "message": "not yet"}}"""
        .formatted(sku);
  }

  private static String response(String results) {
    return """
        {"requestId": "batch-1:attempt-1:chunk-0",
         "service": {"version": "0.1.0", "libraries": {"statsforecast": "2.1.1"}},
         "results": [%s]}""".formatted(results);
  }
}
