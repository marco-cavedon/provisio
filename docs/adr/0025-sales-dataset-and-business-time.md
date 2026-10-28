# 0025 — Sales dataset, demand derivation and business time

- **Status:** Accepted — names the dataset required by [ADR 0002](0002-domain-and-mvp-scope.md) and amends the
  batch trigger of [ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md)
- **Date:** 2026-10-28

## Context and problem statement

ADR 0002 requires a public retail sales dataset for demand history but does not name it. Requirement R4 of the
[roadmap](../roadmap.md#r4--the-sales-dataset-is-chosen-and-its-license-verified) asks for a dataset whose license
allows redistribution, or whose download needs no personal credentials, so the demo still starts with a single
`docker compose up` ([ADR 0007](0007-local-runtime-with-docker-compose.md)).

Choosing a historical dataset has a second consequence: the demo's "today" is a date inside the dataset, not the
wall clock. If forecasting reads the system time, replaying a past period (R8, R9) means rewriting it later.

## Decision drivers

- A license that verifiably allows committing derived data to a public Apache 2.0 repository.
- Real demand, not invented: a forecast that beats a baseline on synthetic data proves little.
- Enough history for three disjoint periods: fit, method selection and backtest (R2), replay (R8).
- No personal data in the repository or in anything sent to the LLM ([ADR 0017](0017-llm-provider-after-spike-001.md)).
- A single product category with a handful of suppliers (ADR 0002).
- Forecasting, batch and replay must work for any business date, not only for "now".

## Considered options

### Dataset

1. **M5 (Walmart, Kaggle competition).** Long, rich history. No authoritative source grants redistribution: the
   data sits behind the competition's terms, and a third-party mirror that labels it CC BY 4.0 cannot relicense
   data it does not own. Downloading it needs a Kaggle account.
2. **Online Retail II (UCI Machine Learning Repository).** Transactions of a UK-based online gift-ware retailer, many
   of whose customers are wholesalers, from 1 December 2009 to 9 December 2011. Licensed CC BY 4.0, downloadable
   without credentials.
3. **Fully synthetic demand.** Known ground truth and every demand class on demand, but no credibility as evidence.

### Business time

1. Forecasting and batch use the system clock; replay is solved later.
2. An explicit business date (`asOf`) everywhere from the start.

## Decision outcome

Dataset option 2 and business-time option 2.

### Dataset and redistribution

- **Online Retail II**, [UCI dataset 502](https://archive.ics.uci.edu/dataset/502/online+retail+ii), CC BY 4.0.
  Attribution goes in [`NOTICE`](../../NOTICE).
- The repository commits a **derived, aggregated subset** only: daily demand per SKU for the chosen category and the
  store's opening calendar. No invoice lines, customer identifiers or countries.
- A **derivation script** in `seed/` rebuilds the subset from the original archive and refuses to run if its
  SHA-256 differs from the recorded one
  (`572e36277c2390fbfde10664750731e0a86f55e33470d91919085f0408e67bfb` for `online_retail_ii.zip` as downloaded on
  28 October 2026; UCI publishes no checksum of its own). Compose never downloads the dataset.

### Demand derivation rules

The exploratory analysis behind these rules found each issue in the original data:

- **Overlapping sheets.** The two worksheets overlap on 1–9 December 2010 with identical lines. Lines are taken
  from the first sheet up to 30 November 2010 and from the second from 1 December 2010. No generic de-duplication:
  repeated lines inside one invoice can be legitimate.
- **Products only.** Non-product codes (postage, manual entries, fees, discounts, adjustments, gift vouchers, test
  codes) are excluded, as are lines with zero or negative price.
- **Cancellations are not demand.** A cancellation invoice (`C` prefix) that matches an earlier order line removes
  that line's demand. The largest orders in the dataset (80,995 and 74,215 units) were cancelled within minutes;
  dropping cancellation invoices alone would keep them as demand. Unmatched cancellations are returns of older
  sales and do not reduce demand. The matching rule is specified with the derivation script.
- **Outliers are kept and documented**, not removed silently. Whether large wholesale orders are forecast or
  treated as a separate kind of demand is a forecasting decision (R1).
- **Opening calendar.** A day is open if the store recorded at least one transaction. Of 739 calendar days, 135 are
  closed: Saturdays, UK bank holidays and a Christmas shutdown of about ten days. Series are zero-filled on open
  days only; counting closed days as zero demand would inflate the average inter-demand interval by about a fifth.
- **Partial last day.** The dataset ends at midday on 9 December 2011; that day is excluded.

### Category

- The category is a **curated, versioned list of SKUs**, not a keyword match on descriptions: a keyword such as
  "BAG" also matches marbles, skittles and sticker sets sold in bags.
- Selection criteria: a few dozen SKUs; history covering all three periods; a mix of demand classes; seasonality
  close to the store average, since one season of fit cannot estimate a strong annual peak.
- The analysis selected **bags** (lunch, jumbo, charlotte, shopper, tote and similar): about 45 SKUs ranging from
  near-daily sales to sales every five to seven open days. The list lives in the seed and its README; it is a data
  choice, so changing it needs no new ADR.
- Rejected: Christmas items. The replay would cover their peak, but about three quarters of their annual demand
  falls in September–November and the fit period holds a single season.

### Granularity and periods

- **Daily granularity on open days.** Coarser buckets are derived by summing quantities; forecasts, demand classes
  and safety stock are recomputed at the coarser granularity, never summed, so each records the granularity it was
  computed at.
- **Three disjoint periods**, so no result is measured on data used to choose it:

| Period | Dates | Used for |
| --- | --- | --- |
| Fit | 1 Dec 2009 – 31 Dec 2010 | Model fitting, demand classification |
| Selection | 1 Jan 2011 – 30 Jun 2011 | Method choice, R2 backtest with rolling origin |
| Replay | 1 Jul 2011 – 8 Dec 2011 | R8 indicators only; never used to choose methods or parameters |

### Business time

- Every forecast request, the batch worker and every stored forecast version carry an explicit **`asOf`** date.
  The history used is strictly earlier than `asOf`; `asOf` is the first forecast day.
- In the Spring application, domain code reads time from an injected `java.time.Clock`, never from `now()`.
  Which component advances the business clock during a replay is decided by spike 003 (R9).
- The forecast batch process has **two start events**: the nightly timer of ADR 0006, for the live demo, and a
  message start event, for replay and manual runs. `asOf` is a process variable; the timer path sets it from the
  business clock. The rest of ADR 0006 still holds.

## Consequences

### Positive

- Data in the repository has a verified license, and the demo needs no download and no credentials.
- Cleaning rules are explicit, versioned and reproducible from a checksummed source.
- Backtest and replay results cannot leak into each other.
- Forecasting, batch and replay share one notion of time, so v0.5.0 does not rewrite them.

### Negative

- Two years of history: annual seasonality cannot be estimated credibly, and only one Christmas season falls in
  the fit period.
- Demand is wholesale-heavy and therefore mostly lumpy: among SKUs covering all three periods, almost none is
  smooth. Methods for regular seasonal series may have nothing to apply to at daily granularity; R1 must be decided
  on this evidence.
- A derivation script and a curated SKU list are more to maintain than a raw file.
- `asOf` and the second start event add a parameter to every forecasting contract and a path to the batch process
  before replay exists.
