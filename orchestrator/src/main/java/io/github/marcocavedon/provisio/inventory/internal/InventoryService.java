package io.github.marcocavedon.provisio.inventory.internal;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.mongodb.client.result.UpdateResult;
import io.github.marcocavedon.provisio.inventory.IdempotencyKeyConflictException;
import io.github.marcocavedon.provisio.inventory.InventoryOperations;
import io.github.marcocavedon.provisio.inventory.InventoryQueries;
import io.github.marcocavedon.provisio.inventory.MovementResult;
import io.github.marcocavedon.provisio.inventory.MovementResult.Reason;
import io.github.marcocavedon.provisio.inventory.Product;
import io.github.marcocavedon.provisio.inventory.ProductRegistration;
import io.github.marcocavedon.provisio.inventory.RecordMovement;
import io.github.marcocavedon.provisio.inventory.StockLevel;
import io.github.marcocavedon.provisio.inventory.internal.TransactionRetry.ConcurrentSnapshotCreationException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service of the inventory module: the only writer of {@code stock_movements} and {@code stock_levels}.
 *
 * <p>Write path (ADR 0022), in one multi-document transaction:
 * <ol>
 *   <li>the SKU must be a known product, otherwise {@code UNKNOWN_SKU} and nothing is written;</li>
 *   <li>insert the movement; a duplicate idempotency key aborts the transaction and the movement recorded the first
 *       time is returned;</li>
 *   <li>update the stock level with {@code $inc}, filtered so it matches only if the result is not negative;
 *       otherwise abort and report {@code INSUFFICIENT_STOCK}.</li>
 * </ol>
 *
 * <p>Step 3 deliberately does not use an upsert. With the guard in the filter, an upsert on an existing SKU with
 * insufficient stock would match nothing and then try to insert a second document with the same {@code _id}: the
 * caller would get a duplicate key error instead of {@code INSUFFICIENT_STOCK}. A missing stock level is handled
 * explicitly instead: a positive first movement creates it, a negative one is insufficient stock.
 */
@Service
class InventoryService implements InventoryQueries, InventoryOperations {

  private final MongoTemplate mongoTemplate;
  private final TransactionTemplate transaction;
  private final Clock clock;

  InventoryService(MongoTemplate mongoTemplate, PlatformTransactionManager transactionManager, Clock clock) {
    this.mongoTemplate = mongoTemplate;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Optional<StockLevel> findStockLevel(String sku) {
    Objects.requireNonNull(sku, "sku");
    StockLevelDocument level = mongoTemplate.findById(sku, StockLevelDocument.class);
    if (level != null) {
      return Optional.of(level.toStockLevel());
    }
    // A known product without movements has no stock level document yet: its stock is zero, not unknown
    return productExists(sku) ? Optional.of(new StockLevel(sku, 0, null)) : Optional.empty();
  }

  @Override
  public Optional<Product> findProduct(String sku) {
    Objects.requireNonNull(sku, "sku");
    return Optional.ofNullable(mongoTemplate.findById(sku, ProductDocument.class)).map(ProductDocument::toProduct);
  }

  @Override
  public MovementResult recordMovement(RecordMovement command) {
    Objects.requireNonNull(command, "command");
    try {
      return TransactionRetry.execute(() -> transaction.execute(status -> write(command)));
    } catch (DuplicateIdempotencyKey duplicate) {
      // The transaction is already aborted by the server: the existing movement can only be read outside it
      return alreadyRecorded(command);
    } catch (InsufficientStock insufficient) {
      return new MovementResult.Rejected(Reason.INSUFFICIENT_STOCK);
    }
  }

  @Override
  public ProductRegistration registerProducts(List<Product> products) {
    Objects.requireNonNull(products, "products");
    validate(products);
    return transaction.execute(status -> {
      Map<String, ProductDocument> existing = new HashMap<>();
      mongoTemplate.find(query(where("_id").in(products.stream().map(Product::sku).toList())), ProductDocument.class)
          .forEach(product -> existing.put(product.sku(), product));
      int inserted = 0;
      int updated = 0;
      for (Product product : products) {
        ProductDocument document = ProductDocument.from(product);
        ProductDocument current = existing.get(product.sku());
        if (current == null) {
          mongoTemplate.insert(document);
          inserted++;
        } else if (!current.equals(document)) {
          mongoTemplate.save(document);
          updated++;
        }
      }
      return new ProductRegistration(inserted, updated, products.size() - inserted - updated);
    });
  }

  private static void validate(List<Product> products) {
    Set<String> skus = new HashSet<>();
    for (Product product : products) {
      if (isBlank(product.sku()) || isBlank(product.name()) || isBlank(product.category()) || isBlank(product.unit())) {
        throw new IllegalArgumentException("Every product field is required: " + product);
      }
      if (!skus.add(product.sku())) {
        throw new IllegalArgumentException("Repeated SKU: " + product.sku());
      }
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** One attempt, run inside a transaction. Business rejections that wrote something abort it by throwing. */
  private MovementResult write(RecordMovement command) {
    if (!productExists(command.sku())) {
      return new MovementResult.Rejected(Reason.UNKNOWN_SKU);
    }

    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    StockMovementDocument movement = StockMovementDocument.from(command, new ObjectId(), now);
    try {
      mongoTemplate.insert(movement);
    } catch (DuplicateKeyException duplicate) {
      // The _id is new, so the duplicate is the idempotency key
      throw new DuplicateIdempotencyKey();
    }

    applyToStockLevel(movement, now);
    return new MovementResult.Recorded(movement.toStockMovement(), false);
  }

  private void applyToStockLevel(StockMovementDocument movement, Instant now) {
    String sku = movement.sku();
    int delta = movement.delta();

    UpdateResult result = mongoTemplate.updateFirst(
        query(where("_id").is(sku).and("onHand").gte(-delta)),
        new Update().inc("onHand", delta).set("lastMovementId", movement.id()).set("updatedAt", now),
        StockLevelDocument.class);
    if (result.getMatchedCount() == 1) {
      return;
    }

    // No match: either the stock is insufficient, or this is the first movement of the SKU
    boolean levelExists = mongoTemplate.exists(query(where("_id").is(sku)), StockLevelDocument.class);
    if (levelExists || delta < 0) {
      throw new InsufficientStock();
    }
    try {
      mongoTemplate.insert(new StockLevelDocument(sku, delta, movement.id(), now));
    } catch (DuplicateKeyException createdConcurrently) {
      // Another transaction created it after this one started: retrying sees it and takes the update path
      throw new ConcurrentSnapshotCreationException(sku, createdConcurrently);
    }
  }

  private MovementResult alreadyRecorded(RecordMovement command) {
    StockMovementDocument existing = mongoTemplate.findOne(
        query(where("idempotencyKey").is(command.idempotencyKey())), StockMovementDocument.class);
    if (existing == null) {
      throw new IllegalStateException(
          "Duplicate idempotency key '" + command.idempotencyKey() + "' but no movement found with it");
    }
    if (!existing.describes(command)) {
      throw new IdempotencyKeyConflictException(command.idempotencyKey(), existing.id().toHexString());
    }
    return new MovementResult.Recorded(existing.toStockMovement(), true);
  }

  private boolean productExists(String sku) {
    return mongoTemplate.exists(query(where("_id").is(sku)), ProductDocument.class);
  }

  /**
   * Control-flow signals that abort the transaction for a business reason. They carry no stack trace and no cause,
   * so they are cheap and never mistaken for a retryable failure.
   */
  private static final class DuplicateIdempotencyKey extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DuplicateIdempotencyKey() {
      super("duplicate idempotency key", null, false, false);
    }
  }

  private static final class InsufficientStock extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InsufficientStock() {
      super("insufficient stock", null, false, false);
    }
  }
}
