package io.github.marcocavedon.provisio.inventory;

/**
 * Outcome of {@link InventoryOperations#registerProducts}.
 *
 * @param inserted products that did not exist
 * @param updated existing products whose name, category or unit changed
 * @param unchanged existing products already identical
 */
public record ProductRegistration(int inserted, int updated, int unchanged) {
}
