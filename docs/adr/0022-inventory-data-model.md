# 0022 — Inventory data model: movement ledger with stock snapshot

- **Status:** Accepted
- **Date:** 2026-10-26

## Context and problem statement

Milestone v0.1.0 introduces the `inventory` module ([ADR 0016](0016-spring-modulith.md)): products and stock
levels for a single warehouse ([ADR 0002](0002-domain-and-mvp-scope.md)). Its first consumer is the agent tool
`provisio.inventory.get-stock-level` ([ADR 0019](0019-job-workers-live-in-domain-modules.md)).

Later milestones place three demands on this model ([roadmap](../roadmap.md)):

- **Audit (R6):** a proposal must be explainable, including why stock was at a given level.
- **Replay (R8):** success indicators are computed by replaying a past period, so stock must be reconstructable
  day by day.
- **Correct reorder decisions:** reorder points are compared with the *inventory position*, not with physical stock.

The sales dataset is not chosen yet ([roadmap R4](../roadmap.md#r4--the-sales-dataset-is-chosen-and-its-license-verified)).

## Decision drivers

- Every stock change must be traceable to a cause.
- Stock must never be silently wrong: no lost updates, no duplicates from retried jobs, no negative stock.
- Module boundaries follow the domain; `inventory` must not depend on `purchasing`.
- MVP scale: tens of SKUs, thousands of movements. Simplicity beats throughput.

## Considered options

1. **Snapshot only:** one document per SKU with its on-hand quantity, updated in place.
2. **Ledger only:** append-only movements; stock is computed by summing them on every read.
3. **Ledger with snapshot:** append-only movements plus a per-SKU snapshot, both written in the same transaction.

## Decision outcome

Option 3. Option 1 loses history, which defeats R6 and R8. Option 2 keeps history but makes every read an
aggregation that grows with time, and moves the "no negative stock" check into a read-then-write race.

### Collections

All three are owned by the `inventory` module; the orchestrator is their only writer
([ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md)).

**`products`** — minimal product master.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | string | The SKU. Opaque identifier, not parsed; its format follows the dataset chosen for R4 |
| `name` | string | |
| `category` | string | |
| `unit` | string | Unit of measure, e.g. `EA` |

**`stock_movements`** — append-only ledger. Documents are never updated or deleted.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | ObjectId | |
| `sku` | string | References `products._id` |
| `type` | string | `RECEIPT`, `ISSUE` or `ADJUSTMENT` |
| `delta` | int | Signed quantity: positive for `RECEIPT`, negative for `ISSUE`, either sign for `ADJUSTMENT` |
| `occurredAt` | date | Business time: when the movement happened. Used by replay |
| `recordedAt` | date | System time: when it was written |
| `idempotencyKey` | string | Supplied by the caller; see below |
| `reference` | object, optional | Cause of the movement, e.g. `{ kind: "purchase-order", id: "…" }` |
| `reason` | string, optional | Required for `ADJUSTMENT` |

**`stock_levels`** — current snapshot, one document per SKU.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | string | The SKU |
| `onHand` | int | Sum of all `delta` values for the SKU; never negative |
| `lastMovementId` | ObjectId | Last movement applied |
| `updatedAt` | date | |

Quantities are whole numbers of the product's unit. Fractional quantities are out of scope for the MVP.

### Indexes

- `stock_movements`: `{ sku: 1, occurredAt: 1 }` for per-SKU history and replay; unique `{ idempotencyKey: 1 }`.
- `products`: `{ category: 1 }`.
- `stock_levels` needs only `_id`.

Indexes are created by the orchestrator at startup, idempotently, in code owned by the `inventory` module.
Spring Data's automatic index creation stays disabled: index definitions are explicit and reviewed.

### Write path

A movement is recorded only through the module's application service, in one multi-document transaction
(available because MongoDB runs as a replica set, [ADR 0007](0007-local-runtime-with-docker-compose.md)):

1. Insert the movement. A duplicate `idempotencyKey` means the movement was already recorded: return the
   existing outcome and change nothing.
2. Update the snapshot with an atomic `$inc` of `onHand` by `delta`, filtered so the update matches only if the
   result is not negative; the first movement for a SKU creates the snapshot.
3. If the filter does not match, abort the transaction and report insufficient stock as a business outcome.

Transactions that fail with a transient error are retried a bounded number of times; anything else fails the
operation (and, for a job worker, the job, so the engine retries and raises an incident).

**Idempotency is required, not optional.** Camunda delivers jobs at least once: a worker can run again after a
timeout even if its first attempt succeeded. Without an idempotency key, a retried "receive goods" job would
count the same delivery twice. Callers derive the key from the business event (for example the purchase order
and line), never from a random value.

### Public API of the module

Other modules see two interfaces; repositories and documents stay in internal packages, verified by
`ApplicationModules.verify()`:

- **Queries:** stock level by SKU, returning `sku`, `onHand` and `asOf`; product by SKU.
- **Operations:** record a movement, returning the recorded movement or a business outcome
  (`INSUFFICIENT_STOCK`, `UNKNOWN_SKU`).

### Job worker `provisio.inventory.get-stock-level`

- **Input:** `sku`.
- **Output:** a single variable `stockLevel` with `sku`, `found`, `onHand` and `asOf`.
- **Unknown SKU** is a business outcome, returned as `found: false` with no other data. It is not a BPMN error
  and it does not list other SKUs ([spike 001](../spikes/001-llm-provider-and-ai-agent-connector.md)).
- **Technical failures** (database unreachable) fail the job so the engine retries.

### Inventory position stays outside this module

Inventory position is on-hand plus on-order minus backorders. On-order quantities belong to `purchasing`
(milestone v0.4.0). Computing the position in `inventory` would make it depend on `purchasing`, inverting the
domain. `inventory` exposes on-hand only; the position is computed where reorder decisions are made, and the agent
is never given on-hand alone as if it were the position.

### Consistency check

A test verifies that, for every SKU, `onHand` equals the sum of its movements' `delta`. The same check can later
run as an operational report.

### Not decided here

- **Daily sales history** for forecasting is not stored as stock movements: at dataset scale it would mean
  millions of documents. It will be modelled with the dataset decision (milestone v0.2.0); a MongoDB time series
  collection is the leading option.
- **Schema validation** with MongoDB `$jsonSchema` validators is deferred; validation lives in the application service.
- **Database access control:** the orchestrator is the first application client of MongoDB, which is the trigger
  to revisit [ADR 0020](0020-local-mongodb-access-control.md). That is a separate decision.

## Consequences

### Positive

- Every stock level can be explained movement by movement and reconstructed at any past date.
- Retried jobs cannot duplicate movements, and stock cannot go negative.
- `inventory` has no dependency on other domain modules.

### Negative

- Two writes per movement inside a transaction, and a snapshot that must never be written directly.
- Callers must supply a meaningful idempotency key; a careless key (random, or too coarse) silently breaks the guarantee.
- The ledger grows without bound; a retention or archiving policy will be needed beyond the MVP.
