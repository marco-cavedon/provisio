package io.github.marcocavedon.provisio.inventory;

import java.util.Optional;

/**
 * Read side of the inventory module.
 */
public interface InventoryQueries {

  /**
   * Current on-hand stock of a SKU.
   *
   * @return empty if the SKU is not a known product; for a known product that has never had a movement, a stock
   *     level with {@code onHand} 0 and no {@code asOf}
   */
  Optional<StockLevel> findStockLevel(String sku);

  /**
   * Product master data of a SKU.
   *
   * @return empty if the SKU is not a known product
   */
  Optional<Product> findProduct(String sku);
}
