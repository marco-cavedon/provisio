package io.github.marcocavedon.provisio.seed.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.marcocavedon.provisio.MongoTestcontainersConfiguration;
import io.github.marcocavedon.provisio.demand.DailyDemand;
import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.demand.DemandQueries;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.Product;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import io.github.marcocavedon.provisio.seed.internal.SeedLoader.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seed loading at startup (ADR 0026). Requires Docker.
 *
 * <p>{@code provisio.seed.dir} is not set, so the context has no loader and shares the other tests' context and
 * container; each test builds a loader on a dataset folder of its own.
 */
@SpringBootTest(properties = {
    "camunda.client.worker.defaults.enabled=false",
    "MONGODB_APP_PASSWORD=unused-connection-details-come-from-the-container"
})
@Import(MongoTestcontainersConfiguration.class)
class SeedLoaderTests {

  /** The committed dataset; Maven runs the tests from the orchestrator folder. */
  private static final Path COMMITTED_SEED = Path.of("../seed/data/online-retail-ii");

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
  private PlatformTransactionManager transactionManager;

  @TempDir
  private Path dir;

  @BeforeEach
  void setUp() {
    for (String collection : new String[] {"demand_history", "business_calendar", SeedLoadDocument.COLLECTION,
        "products", "stock_movements", "stock_levels"}) {
      mongoTemplate.remove(new Query(), collection);
    }
  }

  @Test
  void loadsAnEmptyDatabaseAndRecordsTheLoad() throws IOException {
    writeDataset("A,2011-01-03,5\nA,2011-01-05,2\n");

    assertThat(loader(dir).load()).isEqualTo(Outcome.LOADED);

    assertThat(demandQueries.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 6))).containsExactly(
        new DailyDemand(LocalDate.of(2011, 1, 3), 5),
        new DailyDemand(LocalDate.of(2011, 1, 4), 0),
        new DailyDemand(LocalDate.of(2011, 1, 5), 2));
    SeedLoadDocument record = mongoTemplate.findById("test-dataset", SeedLoadDocument.class);
    assertThat(record).isNotNull();
    assertThat(record.manifestSha256()).isEqualTo(SeedManifest.sha256(Files.readAllBytes(dir.resolve("manifest.json"))));
    assertThat(record.rows()).isEqualTo(2 + 4 + 2);
    assertThat(inventoryQueries.findProduct("A")).contains(new Product("A", "BAG A", "BAGS", "EA"));
    assertThat(inventoryQueries.findStockLevel("B")).contains(new StockLevel("B", 0, null));
    assertThat(record.loadedAt()).isNotNull();
  }

  @Test
  void sameManifestIsNotLoadedAgain() throws IOException {
    writeDataset("A,2011-01-03,5\n");
    loader(dir).load();

    assertThat(loader(dir).load()).isEqualTo(Outcome.UNCHANGED);
  }

  @Test
  void changedManifestReplacesTheData() throws IOException {
    writeDataset("A,2011-01-03,5\nB,2011-01-03,1\n");
    loader(dir).load();
    writeDataset("A,2011-01-04,9\n", "A,BAG A RENAMED,BAGS,EA\n");

    assertThat(loader(dir).load()).isEqualTo(Outcome.LOADED);
    assertThat(inventoryQueries.findProduct("A")).map(Product::name).contains("BAG A RENAMED");
    // Dropped from the seed, but never deleted (ADR 0027)
    assertThat(inventoryQueries.findProduct("B")).isPresent();
    assertThat(demandQueries.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 5)))
        .extracting(DailyDemand::quantity).containsExactly(0, 9);
    assertThat(demandQueries.dailyDemand("B", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 4)))
        .extracting(DailyDemand::quantity).containsExactly(0);
  }

  @Test
  void fileThatDoesNotMatchItsManifestStopsTheLoadAndChangesNothing() throws IOException {
    writeDataset("A,2011-01-03,5\n");
    loader(dir).load();
    String loaded = mongoTemplate.findById("test-dataset", SeedLoadDocument.class).manifestSha256();
    writeDataset("A,2011-01-03,6\n");
    Files.writeString(dir.resolve("daily-demand.csv"), "sku,day,quantity\nA,2011-01-03,7\n");

    assertThatThrownBy(() -> loader(dir).load())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match its manifest");
    assertThat(mongoTemplate.findById("test-dataset", SeedLoadDocument.class).manifestSha256()).isEqualTo(loaded);
    assertThat(demandQueries.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 4)))
        .extracting(DailyDemand::quantity).containsExactly(5);
  }

  @Test
  void missingFolderStopsTheLoad() {
    assertThatThrownBy(() -> loader(dir.resolve("missing")).load())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Seed file not found");
  }

  @Test
  void dataAndRecordAreWrittenInOneTransaction() throws IOException {
    writeDataset("A,2011-01-03,5\n");
    loader(dir).load();
    writeDataset("A,2011-01-03,8\n", "A,BAG A,BAGS,EA\nC,BAG C,BAGS,EA\n");

    // The loader joins an enclosing transaction: if it rolls back, neither the data nor the record change
    assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      loader(dir).load();
      throw new IllegalStateException("failure after the load, before commit");
    })).hasMessageContaining("before commit");

    assertThat(demandQueries.dailyDemand("A", LocalDate.of(2011, 1, 3), LocalDate.of(2011, 1, 4)))
        .extracting(DailyDemand::quantity).containsExactly(5);
    assertThat(inventoryQueries.findProduct("C")).isEmpty();
    assertThat(loader(dir).load()).isEqualTo(Outcome.LOADED);
    assertThat(inventoryQueries.findProduct("C")).isPresent();
  }

  @Test
  void loadsTheCommittedDataset() {
    assertThat(loader(COMMITTED_SEED).load()).isEqualTo(Outcome.LOADED);

    assertThat(mongoTemplate.getCollection("products").countDocuments()).isEqualTo(54);
    assertThat(inventoryQueries.findProduct("85099B"))
        .contains(new Product("85099B", "JUMBO BAG RED RETROSPOT", "BAGS", "EA"));
    assertThat(inventoryQueries.findStockLevel("85099B")).map(StockLevel::onHand).contains(0);

    var calendar = demandQueries.calendar().orElseThrow();
    assertThat(calendar.from()).isEqualTo(LocalDate.of(2009, 12, 1));
    assertThat(calendar.to()).isEqualTo(LocalDate.of(2011, 12, 8));
    assertThat(calendar.closedDays()).hasSize(135);
    assertThat(mongoTemplate.getCollection("business_calendar").countDocuments()).isEqualTo(3);
    // Every series covers the open days only. Saturdays are almost all closed, but not all: the store traded on
    // Saturday 5 December 2009, so the check is against the calendar, not the day of the week.
    assertThat(demandQueries.dailyDemand("85099B", calendar.from(), calendar.to().plusDays(1)))
        .hasSize(603)
        .allMatch(day -> calendar.isOpen(day.day()))
        .anyMatch(day -> day.day().equals(LocalDate.of(2009, 12, 5)));
  }

  private SeedLoader loader(Path datasetDir) {
    return new SeedLoader(datasetDir, mongoTemplate, demandImport, inventoryOperations, transactionManager);
  }

  private void writeDataset(String demandRows) throws IOException {
    writeDataset(demandRows, "A,BAG A,BAGS,EA\nB,BAG B,BAGS,EA\n");
  }

  /**
   * Writes a dataset like seed/derive does: calendar 2011-01-03..2011-01-06 (all open), the given demand and product
   * rows, and a manifest with their checksums.
   */
  private void writeDataset(String demandRows, String productRows) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("calendar.csv", "day,open\n2011-01-03,true\n2011-01-04,true\n2011-01-05,true\n2011-01-06,true\n");
    files.put("daily-demand.csv", "sku,day,quantity\n" + demandRows);
    files.put("skus.csv", "sku,description,category,unit\n" + productRows);
    StringBuilder listed = new StringBuilder();
    for (var file : files.entrySet()) {
      byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
      Files.write(dir.resolve(file.getKey()), content);
      long rows = file.getValue().chars().filter(c -> c == '\n').count() - 1;
      listed.append(listed.isEmpty() ? "" : ", ")
          .append('"').append(file.getKey()).append("\": {\"sha256\": \"").append(SeedManifest.sha256(content))
          .append("\", \"rows\": ").append(rows).append('}');
    }
    Files.writeString(dir.resolve("manifest.json"),
        "{\"dataset\": \"test-dataset\", \"files\": {" + listed + "}}\n");
  }
}
