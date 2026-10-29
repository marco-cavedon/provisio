package io.github.marcocavedon.provisio.inventory.internal;

import io.github.marcocavedon.provisio.inventory.Product;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * {@code products}: minimal product master (ADR 0022).
 *
 * <p>The SKU is the {@code _id}. {@code @MongoId(STRING)} keeps it a string: with a plain {@code @Id}, Spring Data
 * would store a SKU that happens to look like an ObjectId (24 hex digits) as an ObjectId, and the SKU is opaque.
 */
@Document(collection = ProductDocument.COLLECTION)
public record ProductDocument(
    @MongoId(FieldType.STRING) String sku,
    String name,
    String category,
    String unit) {

  public static final String COLLECTION = "products";

  static ProductDocument from(Product product) {
    return new ProductDocument(product.sku(), product.name(), product.category(), product.unit());
  }

  Product toProduct() {
    return new Product(sku, name, category, unit);
  }
}
