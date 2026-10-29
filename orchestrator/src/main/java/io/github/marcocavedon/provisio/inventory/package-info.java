/**
 * Inventory module: products and stock levels for a single warehouse (ADR 0022).
 *
 * <p>Stock changes are recorded as an append-only ledger of movements, with a per-SKU snapshot updated in the same
 * transaction. Other modules use only the types in this package:
 *
 * <ul>
 *   <li>{@link io.github.marcocavedon.provisio.inventory.InventoryQueries} to read stock levels and products;</li>
 *   <li>{@link io.github.marcocavedon.provisio.inventory.InventoryOperations} to record a movement and to register
 *       products (inserted or updated, never deleted: ADR 0027).</li>
 * </ul>
 *
 * <p>Documents, repositories and index definitions live in internal sub-packages, hidden by Spring Modulith.
 * The module exposes on-hand stock only: the inventory position (on-hand plus on-order minus backorders) is computed
 * where reorder decisions are made, because on-order quantities belong to purchasing.
 */
package io.github.marcocavedon.provisio.inventory;
