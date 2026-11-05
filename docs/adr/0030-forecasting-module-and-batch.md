# 0030 — Forecasting module and forecast batch

- **Status:** Accepted
- **Date:** 2026-11-04

## Context and problem statement

[ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md) puts the forecast batch in the Spring application:
it reads the history, calls the stateless forecasting service, and stores versioned forecasts that a purchase-order
proposal can refer to. [ADR 0028](0028-forecasting-contract-and-toolchain.md) fixes the contract and leaves the Java
client to this step. What is left to decide:

- where the module's boundaries are, and what it exposes;
- how forecasts and runs are stored;
- how a batch stays correct when its job is delivered more than once;
- where the replenishment policy comes from before supplier data exists (v0.3.0);
- how the generated client is built, inside and outside Docker, and how it is tested against the real service.

## Decision drivers

- One owner per collection, module boundaries verified by Spring Modulith ([ADR 0016](0016-spring-modulith.md)).
- Every agent decision traceable to the exact forecast and the assumptions it was computed with.
- Zeebe delivers a job at least once: after a failure, or after a timeout while the first attempt is still running.
- The generated client must not leak into the rest of the application, nor its generator's choices.
- The Java request must be proven to pass the Python service's validation, not only to match a document.

## Considered options

### Idempotency of the batch

1. **One transaction for the whole run.** All or nothing, but a long transaction holding a whole batch, and a retry
   recomputes everything.
2. **A random run identifier per attempt.** Simple, and every retry creates a second set of forecasts.
3. **A run identifier stable across retries, resumable runs and a natural key per forecast.**

### Where the policy comes from

1. **Fields on the product in inventory.** Lead times belong to suppliers, not to products: the wrong owner.
2. **Configuration defaults, behind one method, stored with every forecast**, until supplier data replaces them.

### Testing against the service

1. **A mocked HTTP server only.** Fast, and it checks the Java side against what the test author thinks the service
   accepts.
2. **The real service in a container, built from its Dockerfile,** plus a mocked server for the cases the real one
   cannot produce yet.

## Decision outcome

Idempotency option 3, policy option 2, testing option 2.

### Module

`forecasting` depends on `demand` (calendar and series) and `inventory` (the products to forecast); neither depends
on it. `inventory` gains `InventoryQueries.products()`: which products exist is the product master's concern
(ADR 0026 already says so for products without history).

Public API: `ForecastQueries` (`findForecast(id)`, `latestForecast(sku, asOf)`), `Forecast` and
`ReplenishmentPolicy`. Internal: the batch, the documents, and the generated client in
`forecasting.internal.client`, which only `ForecastingClient` sees; it maps the generated types to the module's own
behind the `DemandForecaster` interface. The job worker `provisio.forecasting.run-batch` lives in
`forecasting.worker` ([ADR 0019](0019-job-workers-live-in-domain-modules.md)). The clock bean moves to the shared
configuration, as `InventoryConfiguration` anticipated.

### Data model

**`forecasts`**: one document per product and run, appended, never updated.

| Field | Notes |
| --- | --- |
| `_id` | `runId:sku`, a string: the natural key, and the identifier a proposal refers to |
| `sku`, `runId`, `asOf`, `horizonEnd` | business identity of the version |
| `computedAt` | wall-clock time from the injected `Clock`, for staleness |
| `policy` | `leadTime {meanDays, stdDays?}`, `reviewPeriodDays`, `serviceLevel` |
| `outcome` | `OK` or `FAILED`; failed versions are stored too |
| `demandClass`, `classification`, `method`, `parameters`, `granularity`, `periods`, `protectionInterval` | as the service returns them; `periods` is bounded by the horizon |
| `failure {code, message}` | only when `FAILED` |
| `service {version, libraries}` | reproducibility |

Indexes: `sku_asOf_computedAt` (`sku` ascending, `asOf` and `computedAt` descending) serves "the latest version of a
product as of a business date" from the index alone; `runId_sku` serves the resumption below.

The policy is stored inside the forecast because the quantile depends on it: a version without its assumptions
cannot be interpreted, and a recompute with another lead time (roadmap R10) is a new version, not a correction.

**`forecast_runs`**: one document per run, `_id` = `runId`: `asOf`, `horizonEnd`, `policy`, the Camunda
`processInstanceKey` and `elementInstanceKey` (to find the run in Operate), `startedAt`, `completedAt`, `attempts`,
`status` (`RUNNING`, `COMPLETED`), `requested`, and `ok` and `failed`, counted from `forecasts` on completion.

### Idempotent, resumable batch

- **Stable run identifier:** `batch-{elementInstanceKey}`, the same for every retry of the job. A later activation of
  the task, for example in a loop, is rightly a new run.
- **Resume, do not restart:** each attempt reads which products the run has stored and computes only the others.
- **No transaction across chunks:** forecasts are independent, and a partial run is a valid state to resume from. Two
  attempts running at once (a job that timed out while still running) cannot store a product twice: the second
  insert of the same `_id` is ignored. The cost is computation, not data.
- **One retry mechanism, the engine's:** the worker sets a 10-minute timeout, a 30-second retry backoff and at most
  one active job. Resumption makes a retry cheap.

| Case | Outcome |
| --- | --- |
| Service unreachable, timeout, 5xx | the job fails, the engine retries after the backoff, the next attempt resumes |
| 4xx from the service, a response outside the contract | no retries left: an incident at once, with the problem details |
| `asOf` missing or invalid, no history, `asOf` more than one day after the last observed day, horizon beyond the calendar | the same: retrying cannot help |
| Products `FAILED` | a business outcome: the job completes, `forecastRun.failed` counts them |

Output variable: `forecastRun {runId, asOf, horizonEnd, requested, ok, failed}`. What to do when `ok` is 0 is the
process's decision.

### Request

Products in SKU order, in chunks of `chunk-size` (20, at most 100). Each series is the whole observed history from
the first calendar day to the day before `asOf`, zeros included: how to treat leading zeros of a product launched
later is a choice of the method (roadmap R1), and the caller knows no more than the service. The calendar is sent
from its first day to `horizonEnd`, with the planned part where the horizon reaches it
([ADR 0029](0029-planned-business-calendar.md)). `requestId` is `runId:attempt-n:chunk-m`.

### Policy

`provisio.forecasting.default-policy`: lead time 14 days with no standard deviation, review every 7 days, service
level 0.95; horizon 28 days. They are placeholders until supplier data provides lead times (v0.3.0), and startup
fails if the horizon is shorter than the protection interval. The batch reads them through one method, which v0.3.0
replaces with a per-product lookup.

### Client generation

openapi-generator 7.27.0 (Apache 2.0), Maven plugin, `java` generator with `library=restclient`,
`useSpringBoot4`, `useJackson3`, `useJakartaEe`, `useJspecify`, `openApiNullable=false` (the contract has no
nullable fields). Generated on every build into `target`, never committed. The RestClient comes from Spring Boot's
builder, with the module's timeouts and HTTP/1.1 (the JDK client would otherwise offer an HTTP/2 upgrade, which
uvicorn does not speak). New dependencies: `spring-boot-starter-restclient` (Apache 2.0) and
`jakarta.annotation-api` (EPL 2.0 or GPL 2.0 with the Classpath Exception, the same artifact Spring uses).

The contract is outside the orchestrator folder. The Docker build receives it as the named build context
`contracts` (`docker-compose.yml`, CI, release workflow), so the build context stays the orchestrator folder and the
POM finds `../contracts` inside and outside Docker.

### Tests

- `ForecastingClientTests`, no Docker: the request as the contract defines it, the OK and FAILED mappings, and each
  HTTP failure classified, against `MockRestServiceServer`.
- `ForecastBatchTests`, MongoDB in a container, the service replaced by a fake: chunks, stored versions, resumption
  after a failure, no duplicate under a concurrent attempt, rejected dates, the latest version as of a date, indexes.
- `RunForecastBatchWorkerProcessTests`: Camunda, MongoDB and **the real forecasting service**, built from
  `forecasting/Dockerfile` by Testcontainers. The Java request must pass the Python validation, and the placeholder's
  `FAILED` answers go through the whole path. Testcontainers uses Docker's classic builder, so the service Dockerfile
  uses no BuildKit-only instruction. The orchestrator workflow runs on changes to the contract and to the service.

## Consequences

### Positive

- A retried or duplicated job never duplicates forecasts and never recomputes what is stored.
- Every forecast carries its assumptions, its service version and its business date: a proposal is auditable.
- The generated client is replaceable without touching the batch, and the cross-language contract is tested on the
  wire, end to end.

### Negative

- A run interrupted for good stays `RUNNING` with part of its products; nothing cleans it up yet.
- The policy is the same for every product until v0.3.0.
- The demand module reads the calendar once per product: 54 reads per run, irrelevant now, a query to batch later.
- The process tests build a Python image: the first run is slower, and the orchestrator build now depends on the
  forecasting service's Dockerfile.
- Forecast history grows with every run; retention is still open (ADR 0006).
