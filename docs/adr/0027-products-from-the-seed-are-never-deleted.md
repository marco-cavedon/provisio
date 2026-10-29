# 0027 — Products from the seed are registered, never deleted

- **Status:** Accepted
- **Date:** 2026-10-29

## Context and problem statement

The seed lists the products of the demo category in `skus.csv` ([ADR 0025](0025-sales-dataset-and-business-time.md)),
and the `inventory` module owns the product master `products` ([ADR 0022](0022-inventory-data-model.md)). The seed
module loads data through each owner's public API, in one transaction
([ADR 0026](0026-demand-module-and-seed-loading.md)).

Demand history is replaced as a whole on every new seed version: nothing refers to it. Products are different: the
movement ledger `stock_movements` refers to them by SKU, and its documents are never updated or deleted.

## Decision drivers

- The ledger must never point to a product that no longer exists: stock and its history must stay explainable (R6).
- A new seed version must be able to correct a name, category or unit.
- Loading stays atomic with the rest of the seed.

## Considered options

1. **Replace all products**, like demand history: a product dropped from the seed disappears, and its movements with
   it from every query that starts from the product.
2. **Register: insert or update, never delete.** A product dropped from the seed stays, with its history.
3. **Delete only products without movements**: correct, but a rule with a race (a movement recorded between the check
   and the delete) for a case the demo does not have.

## Decision outcome

Option 2.

- `InventoryOperations.registerProducts(products)` inserts new SKUs, updates name, category and unit of existing ones
  when they differ, and never deletes. It validates the whole input first (no blank field, no repeated SKU) and writes
  in a transaction that joins the caller's, so the seed registers products, replaces demand and records the load
  atomically.
- It reports how many products were inserted, updated and left unchanged.
- Removing a product, if ever needed, is a deliberate operation with its own rules, not a side effect of the seed.

## Consequences

### Positive

- Stock movements always refer to an existing product.
- A corrected description reaches the product master with the next seed version.

### Negative

- Products dropped from the seed accumulate. Acceptable for a demo whose catalogue changes rarely and by hand.
