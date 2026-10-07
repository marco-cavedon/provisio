# 0006 — Precomputed forecast with on-demand recompute

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

The agent needs forecasts quickly and reliably. Computing them on every agent call couples the agent
to model latency; never recomputing them ignores exceptional events such as supplier delays.

A second question follows: who owns the forecasting batch and the persistence of its results —
the Spring application or the Python forecasting service?

## Decision drivers

- A single owner for every MongoDB collection.
- The forecasting service must stay stateless and unaware of Camunda ([ADR 0005](0005-python-stateless-forecasting-service.md)).
- Every agent decision must be traceable to the exact forecast it used.
- Batch runs must be observable and recoverable.

## Considered options

1. **Python owns the batch**: reads sales history and writes forecasts to MongoDB.
2. **Spring owns the batch**: reads history, calls Python as a pure function, persists results.

## Decision outcome

Option 2.

- **Single writer.** Only the Spring application writes to MongoDB. Python receives series and returns
  forecasts; it has no database access. Two services in two languages writing the same collections would
  turn the collection schema into an implicit contract between codebases (integration through the database).
- **Contract-first API.** An OpenAPI specification, versioned in the repository, is the single source of truth.
  The Java client and the Pydantic models are **generated** from it; CI fails if code and contract diverge.
  Data transfer objects are not "shared" across languages: the contract is.
- **Bulk endpoint.** The batch sends series in chunks rather than one HTTP call per SKU.
  On-demand recompute uses the same endpoint with a single item.
- **Versioned forecasts.** Forecasts are appended, never overwritten: each one records an identifier,
  computation timestamp, method and parameters. The agent's purchase-order proposal references the
  identifier of the forecast it used.
- **Batch triggered by Camunda.** A BPMN process with a nightly **timer start event** runs a single service task
  ("run forecast batch") whose worker processes SKUs in chunks. This provides retries, incident handling and
  visibility in the engine's tooling. **No multi-instance per SKU**: one process instance per SKU would load
  the engine with no benefit at realistic volumes.
- **On-demand recompute** is an agent tool, used for anomalous events (e.g. a supplier delay).

## Consequences

### Positive

- One owner per collection; the forecasting service is trivially testable (input in, output out).
- Every proposal can be audited back to a specific forecast version.
- Batch failures surface as incidents instead of silent log lines.

### Negative

- Forecasts can be stale between runs; staleness must be visible to the agent and the user.
- Generated Pydantic models are less idiomatic than hand-written ones.
- Series travel over HTTP; acceptable at MVP scale, to be revisited for large catalogues.
- Forecast history grows over time and will need a retention policy.
