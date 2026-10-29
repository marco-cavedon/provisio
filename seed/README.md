# Seed

Demo data for Provisio. Today it holds the demand history; synthetic supplier documents and their embeddings come
with v0.3.0 ([roadmap](../docs/roadmap.md)).

Nothing in this folder writes to MongoDB. Only the Spring application writes to the database
([ADR 0006](../docs/adr/0006-precomputed-forecast-with-on-demand-recompute.md)): its seed module loads these files at
startup, if they are not loaded already ([ADR 0026](../docs/adr/0026-demand-module-and-seed-loading.md)). Docker
Compose mounts `data/` read-only into the orchestrator.

## Demand history

`data/online-retail-ii/` is a derived, aggregated subset of the
[Online Retail II](https://archive.ics.uci.edu/dataset/502/online+retail+ii) dataset (UCI Machine Learning Repository,
CC BY 4.0; attribution in [`NOTICE`](../NOTICE)). The choice of dataset and the derivation rules are recorded in
[ADR 0025](../docs/adr/0025-sales-dataset-and-business-time.md).

| File | Content |
| --- | --- |
| `daily-demand.csv` | `sku`, `day`, `quantity`: demand per product and day, on days with sales only |
| `calendar.csv` | `day`, `open`: every calendar day from 1 December 2009 to 8 December 2011 |
| `skus.csv` | `sku`, `description`: the products of the category |
| `manifest.json` | Source checksum, periods, summary figures, checksum and row count of every file |

A product with no row for an open day sold nothing that day. A closed day is not a zero: the store was not trading.
No invoice lines, customer identifiers or countries are included.

### Category: bags

[`derive/bags-skus.csv`](derive/bags-skus.csv) lists the candidates found by the exploratory analysis: products whose
description names a bag and whose sales cover the fit, selection and replay periods. Each one is included or excluded
by hand, with the reason for every exclusion (marbles, games or party sets sold in a bag are not bags). The derivation
fails if an included product has no demand in one of the periods.

### Derivation

[`derive/`](derive/) rebuilds the subset from the original archive. Download `online_retail_ii.zip` from the UCI page,
then, with [uv](https://docs.astral.sh/uv/):

```bash
cd seed/derive
uv run derive path/to/online_retail_ii.zip   # refuses an archive whose SHA-256 differs from the recorded one
uv run pytest                                # rules on small fixtures; committed files against the manifest
```

The outputs are deterministic: the same archive and rules give byte-identical files. The rules, each tested on its own
in `derive/tests/test_rules.py`:

1. The two worksheets overlap on 1–9 December 2010: lines come from the first sheet up to 30 November 2010 and from
   the second from 1 December 2010.
2. The last day, 9 December 2011, ends at midday and is excluded.
3. A day is open if the store recorded any transaction.
4. Only product codes (five digits, optionally followed by letters) with a positive price are kept.
5. A cancellation matches an order with the same customer, product and quantity placed on the same day or the day
   before; the order's demand is removed. Other cancellations are returns of older sales and leave demand unchanged.
   Negative quantities outside cancellations are stock adjustments, not demand.
6. Quantities are summed per product and day.

For the bags, cancellations total 12,557 units against 802,330 ordered: 6,997 are matched and removed, 5,560 are
treated as returns.

### Simplifications

- The dataset records **sales, not demand**. When a product was out of stock, demand was higher than sales and that
  difference is invisible. Sales are used as an estimate of unconstrained demand.
- Large wholesale orders are kept as demand. How forecasting treats them is decided with the forecasting methods (R1).
