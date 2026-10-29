package io.github.marcocavedon.provisio.seed.internal;

import io.github.marcocavedon.provisio.inventory.Product;
import java.util.List;

/**
 * The products of a dataset: {@code skus.csv}, as written by {@code seed/derive}, for the inventory product master
 * (ADR 0027). The description becomes the product name.
 */
record ProductSeed(List<Product> products) {

  static final String SKUS = "skus.csv";

  static ProductSeed from(SeedManifest manifest) {
    List<Product> products = manifest.csvRows(SKUS, "sku,description,category,unit").stream()
        .map(row -> new Product(row[0], row[1], row[2], row[3]))
        .toList();
    return new ProductSeed(products);
  }
}
