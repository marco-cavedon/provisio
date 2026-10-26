package io.github.marcocavedon.provisio.inventory;

/**
 * Business cause of a movement, e.g. {@code ("purchase-order", "PO-42")}.
 *
 * @param kind kind of the referenced business object
 * @param id identifier of the referenced business object
 */
public record MovementReference(String kind, String id) {

  public MovementReference {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("reference kind is required");
    }
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("reference id is required");
    }
  }
}
