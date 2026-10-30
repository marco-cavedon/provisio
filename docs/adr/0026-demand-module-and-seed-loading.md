# 0026 — Demand data model and seed loading

- **Status:** Accepted
- **Date:** 2026-10-29

## Context and problem statement

[ADR 0025](0025-sales-dataset-and-business-time.md) commits a derived demand subset to `seed/data/online-retail-ii/`:
daily demand per product on days with sales, and the store's opening calendar. The forecasting batch of
[ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md) needs that history in MongoDB, and only the Spring
application may write to MongoDB (same ADR). Three questions follow:

- How demand history and the calendar are modelled in MongoDB.
- Who loads the seed, and how a reload stays consistent.
- How a calendar day is stored, given that Java has `LocalDate` and BSON has only an instant (`Date`).

## Decision drivers

- Model for the reads that exist. Every read of demand is a series: the batch reads the history of every product
  before `asOf`, an on-demand recompute reads the history of one product. Nobody reads a single day.
- One owner per collection, enforced by Spring Modulith ([ADR 0016](0016-spring-modulith.md)).
- A reload is all or nothing: never a mix of two seed versions, never history without its calendar.
- A day must read back as the same day on every machine, whatever the JVM's default time zone.
- MVP scale: about 22,000 product-days for 54 products; documents must stay bounded if sales kept arriving.

## Considered options

### Demand history

1. **One document per product and day.** Mirrors the CSV rows: a series is about 400 documents to collect.
2. **One document per product with the whole series.** One read per series, but an array that grows without bound.
3. **One document per product and month (bucket pattern).** A series is about 25 documents read in one index scan;
   each document holds at most 31 entries.
4. **A time series collection.** MongoDB buckets the data itself, but writes inside transactions and unique indexes
   are not supported, so neither the atomic reload nor the one-bucket-per-month guarantee is possible.

### Seed loading

1. **A loader in each module**, reading its own files: the checksum and "already loaded" logic is repeated in every
   module, starting with supplier documents in v0.3.0.
2. **A `seed` module** that reads `seed/data`, verifies it, and calls each module's public API inside one
   transaction.
3. **A separate container** importing with `mongoimport` or a script: a second writer, rejected by ADR 0006.

### Calendar day

1. **ISO string** (`"2011-03-14"`): time-zone free and correctly ordered, but untyped, and every date operator needs
   a conversion.
2. **`Date` at midnight UTC**, with explicit converters.

## Decision outcome

Demand option 3, seed option 2, calendar day option 2.

### Collections of the `demand` module

**`demand_history`** — one document per product and month, days with sales only.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | ObjectId | |
| `sku` | string | Opaque, as in `products` ([ADR 0022](0022-inventory-data-model.md)) |
| `month` | date | First day of the month |
| `days` | array | `{ day: date, quantity: int }`, ordered by day, quantity always positive |

Index `sku_month_unique` on `{ sku: 1, month: 1 }`, unique: one bucket per product and month, and the index that
serves every series read.

**`business_calendar`** — one document per year.

| Field | Type | Notes |
| --- | --- | --- |
| `_id` | int | The year |
| `from`, `to` | date | First and last day covered in that year |
| `closedDays` | array of dates | Days the store was not trading; every other day in the range is open |

The calendar is read whole for every series, so it is stored whole: three documents. Closed days are the exception,
so the list stays short and bounded.

**Zero filling happens in one place.** `DemandQueries.dailyDemand` returns a series with one entry per open day in
the requested range and zero where nothing was sold; closed days are omitted. A request outside the calendar fails.

**Not stored: the product description.** No read of demand history needs it: forecasting consumes numbers only.
Copying it would add about 25 copies per product, owned by `inventory`, with nothing to keep them current. The
extended reference pattern belongs where the description is read together with the rest, and must be frozen as it was:
the purchase-order proposal and its audit record (R6).

### Seed loading

- The `seed` module is active only when `provisio.seed.dir` is set (`PROVISIO_SEED_DIR`): Compose mounts
  `./seed/data` read-only into the orchestrator and points it at `/seed/online-retail-ii`; `run-local.sh` points it at
  the repository folder. If the property is set but the folder or its manifest is invalid, startup fails.
- At startup it checks every file against `manifest.json` (SHA-256 and row count), so a hand-edited file is refused.
- **`seed_loads`** records what was loaded: `{ _id: dataset, manifestSha256, rows, loadedAt }`. If the manifest is
  unchanged, nothing happens. Otherwise the demand history and the calendar are replaced and the record is written
  **in one transaction**.
- The `seed` module writes only `seed_loads`. Demand data goes through `DemandImport`, owned by `demand`; products
  will go through an `inventory` operation in the next change.
- `loadedAt` is wall-clock time: a technical timestamp, not business time.

### Calendar day

- A calendar day is a BSON `Date` at **midnight UTC**. It is a label for a day, not an instant.
- Spring Data's default converters for local temporal types use the JVM's default time zone: a `LocalDate` written on
  a machine in Europe/Rome would be stored as 23:00 UTC of the previous day. Explicit converters, registered in the
  shared MongoDB configuration, write and read midnight UTC, and reading a `Date` that is not at midnight UTC fails
  instead of silently shifting the day. Tests run them with the default time zone set to UTC+14 and UTC−8.

## Consequences

### Positive

- Every read of demand is one index scan over a few dozen small documents, and the model explains itself: data read
  together is stored together.
- A reload never leaves a mix of versions; a corrupted seed file stops the application instead of loading.
- The same day on every machine, and date operators (`$dateTrunc`, `$dayOfWeek`) work on the stored fields.
- The next modules to seed (products, supplier documents) reuse the same loader.

### Negative

- Changing one day's quantity means updating an array element; acceptable, since history is replaced as a whole.
- The reload is one transaction of about 22,000 documents, far below MongoDB's limits; a seed two orders of magnitude
  larger would need a staging collection and a swap instead.
- Two orchestrators starting together on a new seed would conflict; one fails and restarts. The local stack runs
  one instance.
- Converters for `LocalDate` are shared configuration every module now depends on.
