package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.demand.DemandImport;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.ProductRegistration;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the dataset in {@code provisio.seed.dir} at startup, if it is not loaded already (ADR 0026).
 *
 * <ol>
 *   <li>Every file is checked against {@code manifest.json}: a hand-edited or truncated file stops the
 *       application.</li>
 *   <li>If {@code seed_loads} records the same manifest, nothing happens: restarts are cheap.</li>
 *   <li>Otherwise, in one transaction: products are registered in inventory (inserted or updated, never deleted,
 *       ADR 0027), the demand history and calendar are replaced, and the load is recorded. A failure leaves the
 *       previous version intact.</li>
 * </ol>
 *
 * <p>Created by {@link SeedConfiguration} only when the property is set. Any failure is thrown, so startup fails.
 */
class SeedLoader implements ApplicationRunner {

  enum Outcome { LOADED, UNCHANGED }

  private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

  private final Path dir;
  private final MongoTemplate mongoTemplate;
  private final DemandImport demandImport;
  private final InventoryOperations inventory;
  private final TransactionTemplate transaction;

  SeedLoader(Path dir, MongoTemplate mongoTemplate, DemandImport demandImport, InventoryOperations inventory,
      PlatformTransactionManager transactionManager) {
    this.dir = dir;
    this.mongoTemplate = mongoTemplate;
    this.demandImport = demandImport;
    this.inventory = inventory;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Override
  public void run(ApplicationArguments args) {
    load();
  }

  Outcome load() {
    SeedManifest manifest = SeedManifest.read(dir);
    SeedLoadDocument loaded = mongoTemplate.findById(manifest.dataset(), SeedLoadDocument.class);
    if (loaded != null && loaded.manifestSha256().equals(manifest.sha256())) {
      log.info("Seed {} already loaded on {}, unchanged", manifest.dataset(), loaded.loadedAt());
      return Outcome.UNCHANGED;
    }

    ProductSeed products = ProductSeed.from(manifest);
    DemandSeed demand = DemandSeed.from(manifest);
    SeedLoadDocument record = new SeedLoadDocument(manifest.dataset(), manifest.sha256(),
        products.products().size() + demand.rows(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    ProductRegistration registration = transaction.execute(status -> {
      ProductRegistration registered = inventory.registerProducts(products.products());
      demandImport.replaceAll(demand.calendar(), demand.entries());
      mongoTemplate.save(record);
      return registered;
    });
    log.info("Seed {} loaded from {}: products {} inserted, {} updated, {} unchanged; {} calendar days, "
            + "{} product-days with sales",
        manifest.dataset(), dir, registration.inserted(), registration.updated(), registration.unchanged(),
        demand.calendar().from().datesUntil(demand.calendar().to().plusDays(1)).count(), demand.entries().size());
    return Outcome.LOADED;
  }
}
