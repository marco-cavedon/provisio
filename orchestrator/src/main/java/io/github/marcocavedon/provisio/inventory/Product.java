package io.github.marcocavedon.provisio.inventory;

/**
 * Minimal product master data.
 *
 * @param sku opaque identifier, never parsed
 * @param name product name
 * @param category product category
 * @param unit unit of measure, e.g. {@code EA}; quantities are whole numbers of this unit
 */
public record Product(String sku, String name, String category, String unit) {
}
