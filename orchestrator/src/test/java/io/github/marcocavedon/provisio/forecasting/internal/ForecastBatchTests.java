package io.github.marcocavedon.provisio.forecasting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.demand.BusinessCalendar;
import io.github.marcocavedon.provisio.demand.DemandEntry;
import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.demand.DemandQueries;
import io.github.marcocavedon.provisio.forecasting.Forecast;
import io.github.marcocavedon.provisio.forecasting.ForecastQueries;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy;
import io.github.marcocavedon.provisio.forecasting.ReplenishmentPolicy.LeadTime;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.RunRequest;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastBatch.RunSummary;
import io.github.marcocavedon.provisio.forecasting.internal.ForecastingProperties.DefaultPolicy;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.Product;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The forecast batch against a real MongoDB, with the forecasting service replaced by an in-memory fake: chunks,
 * stored versions, resumption after a failure, no duplicates, rejected dates (ADR 0030). Requires Docker.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class ForecastBatchTests {

  // Observed in June 2011, planned from 1 July to 31 August; closed on Saturday 4 June and Saturday 2 July
  private static final LocalDate FROM = LocalDate.of(2011, 6, 1);
  private static final LocalDate OBSERVED_TO = LocalDate.of(2011, 6, 30);
  private static final LocalDate TO = LocalDate.of(2011, 8, 31);
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(FROM, OBSERVED_TO, TO, Set.of(LocalDate.of(2011, 6, 4), LocalDate.of(2011, 7, 2)));
  private static final LocalDate AS_OF = LocalDate.of(2011, 7, 1);
  private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
  private static final ReplenishmentPolicy POLICY = new ReplenishmentPolicy(new LeadTime(14, null), 7, 0.95);

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private DemandImport demandImport;

  @Autowired
  private DemandQueries demandQueries;

  @Autowired
  private InventoryOperations inventoryOperations;

  @Autowired
  private InventoryQueries inventoryQueries;

  @Autowired
  private ForecastQueries forecastQueries;

  @Autowired
  private ForecastIndexes indexes;

  private final FakeForecaster forecaster = new FakeForecaster();

  @BeforeEach
  void setUp() {
    for (String collection : new String[] {ForecastDocument.COLLECTION, ForecastRunDocument.COLLECTION,
        "demand_history", "business_calendar", "products", "stock_movements", "stock_levels"}) {
      mongoTemplate.remove(new Query(), collection);
    }
    inventoryOperations.registerProducts(List.of(product("C"), product("A"), product("B")));
    demandImport.replaceAll(CALENDAR, List.of(new DemandEntry("A", LocalDate.of(2011, 6, 1), 5),
        new DemandEntry("B", LocalDate.of(2011, 6, 30), 2)));
  }

  @Test
  void forecastsEveryProductInChunksAndStoresOneVersionEach() {
    forecaster.failing("B");

    RunSummary summary = batch(28).run(request("batch-1", AS_OF));

    assertThat(summary).isEqualTo(new RunSummary("batch-1", AS_OF, LocalDate.of(2011, 7, 28), 3, 2, 1));
    // Chunks of two, products in SKU order, the whole observed history with zeros and without closed days
    assertThat(forecaster.calls).extracting(c -> c.series().stream().map(DemandForecaster.Series::sku).toList())
        .containsExactly(List.of("A", "B"), List.of("C"));
    DemandForecaster.Call first = forecaster.calls.getFirst();
    assertThat(first.requestId()).isEqualTo("batch-1:attempt-1:chunk-0");
    assertThat(first.horizonEnd()).isEqualTo(LocalDate.of(2011, 7, 28));
    assertThat(first.series().getFirst().history()).hasSize(29)
        .first().satisfies(day -> assertThat(day.quantity()).isEqualTo(5));
    assertThat(first.series().getFirst().policy()).isEqualTo(POLICY);

    Forecast a = forecastQueries.findForecast("batch-1:A").orElseThrow();
    assertThat(a.outcome()).isEqualTo(Forecast.Outcome.OK);
    assertThat(a.asOf()).isEqualTo(AS_OF);
    assertThat(a.computedAt()).isEqualTo(NOW);
    assertThat(a.policy()).isEqualTo(POLICY);
    assertThat(a.method()).isEqualTo("FAKE");
    assertThat(a.parameters()).containsEntry("alpha", 0.1);
    assertThat(a.periods()).hasSize(1);
    assertThat(a.service()).isEqualTo(FakeForecaster.SERVICE);
    Forecast b = forecastQueries.findForecast("batch-1:B").orElseThrow();
    assertThat(b.outcome()).isEqualTo(Forecast.Outcome.FAILED);
    assertThat(b.failure()).isEqualTo(new Forecast.Failure("TOO_SHORT", "fake failure"));
    assertThat(b.periods()).isNull();

    Document run = raw(ForecastRunDocument.COLLECTION).getFirst();
    assertThat(run.getString("_id")).isEqualTo("batch-1");
    assertThat(run.getString("status")).isEqualTo("COMPLETED");
    assertThat(run.getInteger("attempts")).isEqualTo(1);
    assertThat(run.getInteger("requested")).isEqualTo(3);
    assertThat(run.getInteger("ok")).isEqualTo(2);
    assertThat(run.getInteger("failed")).isEqualTo(1);
    assertThat(run.getLong("processInstanceKey")).isEqualTo(11L);
  }

  @Test
  void retryResumesFromTheProductsAlreadyStored() {
    forecaster.unavailableOnCall(2);

    assertThatThrownBy(() -> batch(28).run(request("batch-1", AS_OF)))
        .isInstanceOf(ForecastingUnavailableException.class);
    assertThat(raw(ForecastDocument.COLLECTION)).extracting(d -> d.getString("sku")).containsExactlyInAnyOrder("A", "B");
    assertThat(raw(ForecastRunDocument.COLLECTION).getFirst().getString("status")).isEqualTo("RUNNING");

    RunSummary summary = batch(28).run(request("batch-1", AS_OF));

    assertThat(forecaster.calls.getLast().series()).extracting(DemandForecaster.Series::sku).containsExactly("C");
    assertThat(forecaster.calls.getLast().requestId()).isEqualTo("batch-1:attempt-2:chunk-0");
    assertThat(summary.ok()).isEqualTo(3);
    assertThat(raw(ForecastDocument.COLLECTION)).hasSize(3);
    Document run = raw(ForecastRunDocument.COLLECTION).getFirst();
    assertThat(run.getInteger("attempts")).isEqualTo(2);
    assertThat(run.getString("status")).isEqualTo("COMPLETED");
  }

  @Test
  void completedRunRunAgainComputesNothing() {
    batch(28).run(request("batch-1", AS_OF));
    int calls = forecaster.calls.size();

    RunSummary again = batch(28).run(request("batch-1", AS_OF));

    assertThat(forecaster.calls).hasSize(calls);
    assertThat(again.ok()).isEqualTo(3);
    assertThat(raw(ForecastDocument.COLLECTION)).hasSize(3);
  }

  @Test
  void versionStoredByAConcurrentAttemptIsKept() {
    // While this attempt computes C, another attempt of the same run stores it first
    forecaster.duringCall(2, () -> mongoTemplate.getCollection(ForecastDocument.COLLECTION)
        .insertOne(new Document("_id", "batch-1:C").append("sku", "C").append("runId", "batch-1")
            .append("outcome", "OK").append("method", "OTHER-ATTEMPT")));

    RunSummary summary = batch(28).run(request("batch-1", AS_OF));

    assertThat(summary.ok()).isEqualTo(3);
    assertThat(raw(ForecastDocument.COLLECTION)).hasSize(3);
    assertThat(mongoTemplate.getCollection(ForecastDocument.COLLECTION).find(new Document("_id", "batch-1:C"))
        .first().getString("method")).isEqualTo("OTHER-ATTEMPT");
  }

  @Test
  void runIdReusedWithAnotherBusinessDateIsRejected() {
    batch(28).run(request("batch-1", AS_OF));

    assertThatThrownBy(() -> batch(28).run(request("batch-1", AS_OF.minusDays(1))))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("exists with asOf 2011-07-01");
    // The completed run is left as it was
    Document run = raw(ForecastRunDocument.COLLECTION).getFirst();
    assertThat(run.getString("status")).isEqualTo("COMPLETED");
    assertThat(run.getInteger("attempts")).isEqualTo(1);
  }

  @Test
  void datesTheCalendarCannotSupportAreRejectedBeforeAnyCall() {
    // The history must be observed: asOf at most the day after the last observed day
    assertThatThrownBy(() -> batch(28).run(request("batch-1", OBSERVED_TO.plusDays(2))))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("last observed day 2011-06-30");
    // No open day before asOf: nothing to forecast from
    assertThatThrownBy(() -> batch(28).run(request("batch-2", FROM)))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("No open day before asOf");
    // The horizon must stay within the calendar, planned part included
    assertThatThrownBy(() -> batch(90).run(request("batch-3", AS_OF)))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("after the calendar ends on 2011-08-31");

    assertThat(forecaster.calls).isEmpty();
    assertThat(raw(ForecastRunDocument.COLLECTION)).isEmpty();
  }

  @Test
  void runWithoutDemandHistoryIsRejected() {
    mongoTemplate.remove(new Query(), "business_calendar");

    assertThatThrownBy(() -> batch(28).run(request("batch-1", AS_OF)))
        .isInstanceOf(ForecastingRejectedException.class).hasMessageContaining("the seed has not run");
  }

  @Test
  void latestForecastIsTheLastVersionMadeOnOrBeforeTheBusinessDate() {
    batch(28).run(request("batch-1", AS_OF));
    batch(28).run(request("batch-2", AS_OF.minusDays(1)));

    assertThat(forecastQueries.latestForecast("A", AS_OF)).map(Forecast::runId).contains("batch-1");
    assertThat(forecastQueries.latestForecast("A", AS_OF.minusDays(1))).map(Forecast::runId).contains("batch-2");
    assertThat(forecastQueries.latestForecast("A", AS_OF.minusDays(2))).isEmpty();
    assertThat(forecastQueries.latestForecast("UNKNOWN", AS_OF)).isEmpty();
  }

  @Test
  void createsTheIndexesIdempotently() {
    List<Document> found = mongoTemplate.getCollection(ForecastDocument.COLLECTION).listIndexes()
        .into(new ArrayList<>());
    assertThat(found).anySatisfy(index -> {
      assertThat(index.getString("name")).isEqualTo(ForecastIndexes.FORECASTS_BY_SKU_AS_OF_AND_TIME);
      assertThat(index.get("key", Document.class))
          .isEqualTo(new Document("sku", 1).append("asOf", -1).append("computedAt", -1));
    });
    assertThat(found).anySatisfy(index ->
        assertThat(index.getString("name")).isEqualTo(ForecastIndexes.FORECASTS_BY_RUN));
    assertThatCode(indexes::createIndexes).doesNotThrowAnyException();
  }

  private ForecastBatch batch(int horizonDays) {
    ForecastingProperties properties = new ForecastingProperties(URI.create("http://unused"), Duration.ofSeconds(1),
        Duration.ofSeconds(1), 2, horizonDays, new DefaultPolicy(14, null, 7, 0.95));
    return new ForecastBatch(demandQueries, inventoryQueries, forecaster, mongoTemplate, properties,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static RunRequest request(String runId, LocalDate asOf) {
    return new RunRequest(runId, asOf, 11L, 22L);
  }

  private static Product product(String sku) {
    return new Product(sku, "BAG " + sku, "BAGS", "EA");
  }

  private List<Document> raw(String collection) {
    return mongoTemplate.getCollection(collection).find().sort(new Document("_id", 1)).into(new ArrayList<>());
  }

  /** Answers OK for every product except the failing ones; can fail or run an action on the n-th call (1-based). */
  private static final class FakeForecaster implements DemandForecaster {

    static final Forecast.Service SERVICE = new Forecast.Service("test", Map.of("fake", "1.0"));

    final List<Call> calls = new ArrayList<>();
    private Set<String> failing = Set.of();
    private int unavailableOnCall = -1;
    private int actionOnCall = -1;
    private Runnable action = () -> { };

    void failing(String... skus) {
      this.failing = Set.of(skus);
    }

    void unavailableOnCall(int call) {
      this.unavailableOnCall = call;
    }

    void duringCall(int call, Runnable action) {
      this.actionOnCall = call;
      this.action = action;
    }

    @Override
    public Results forecast(Call call) {
      calls.add(call);
      if (calls.size() == unavailableOnCall) {
        throw new ForecastingUnavailableException("fake outage", null);
      }
      if (calls.size() == actionOnCall) {
        action.run();
      }
      List<Result> results = new ArrayList<>();
      for (Series series : call.series()) {
        results.add(failing.contains(series.sku()) ? failed(series.sku()) : ok(series.sku(), call));
      }
      return new Results(SERVICE, results);
    }

    private static Result ok(String sku, Call call) {
      return new Result(sku, Forecast.Outcome.OK, "LUMPY", new Forecast.Classification(2.0, 1.0), "FAKE",
          Map.of("alpha", 0.1), "DAILY", List.of(new Forecast.Period(call.asOf(), call.horizonEnd(), 4.0)),
          new Forecast.ProtectionInterval(call.asOf(), call.asOf().plusDays(20), 18, 3.0, 9.0, 0.95, "FAKE"), null);
    }

    private static Result failed(String sku) {
      return new Result(sku, Forecast.Outcome.FAILED, null, null, null, null, null, null, null,
          new Forecast.Failure("TOO_SHORT", "fake failure"));
    }
  }
}
