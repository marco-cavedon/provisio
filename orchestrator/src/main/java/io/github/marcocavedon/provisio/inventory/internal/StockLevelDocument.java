package io.github.marcocavedon.provisio.inventory.internal;

import io.github.marcocavedon.provisio.inventory.StockLevel;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code stock_levels}: current snapshot, one document per SKU (ADR 0022).
 *
 * <p>Written only by {@link InventoryService}, in the same transaction as the movement it applies. {@code onHand} is
 * always the sum of the SKU's movement deltas and never negative.
 */
@Document(collection = StockLevelDocument.COLLECTION)
public record StockLevelDocument(
    @MongoId(FieldType.STRING) String sku,
    int onHand,
    ObjectId lastMovementId,
    Instant updatedAt) {

  public static final String COLLECTION = "stock_levels";

  StockLevel toStockLevel() {
    return new StockLevel(sku, onHand, updatedAt);
  }
}
