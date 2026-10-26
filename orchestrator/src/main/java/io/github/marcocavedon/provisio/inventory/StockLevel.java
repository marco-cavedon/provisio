package io.github.marcocavedon.provisio.inventory;

import java.time.Instant;
import java.util.Objects;

/**
 * On-hand stock of a SKU.
 *
 * <p>This is physical stock, not the inventory position: it must never be used alone for a reorder decision.
 *
 * @param sku the SKU
 * @param onHand on-hand quantity in the product's unit; never negative
 * @param asOf when the stock level last changed (system time of the last movement applied); {@code null} if the SKU
 *     has never had a movement
 */
public record StockLevel(String sku, int onHand, Instant asOf) {

  public StockLevel {
    Objects.requireNonNull(sku, "sku");
  }
}
