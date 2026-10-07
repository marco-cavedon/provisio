# 0014 — Testing strategy

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

The system mixes deterministic code, process models and a non-deterministic LLM. Tests must be fast,
deterministic and meaningful, and must never depend on paid or rate-limited external services.

## Decision outcome

Minimum test set per component, introduced **when the component is created** (part of its definition of done):

| Component | Tests |
| --- | --- |
| Orchestrator | JUnit unit tests; module-boundary verification; process tests (Camunda Process Test) on BPMN and DMN with LLM and connectors **mocked** |
| Forecasting | `pytest` on the calculation functions (pure functions); `ruff` for lint and formatting |
| Frontend | Lint, type check, build; component tests once there is logic worth testing |
| API contract | Drift check between the OpenAPI specification and generated code ([ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md)) |
| Repository | Secret scanning; Markdown lint and internal link check |

Rules:

- **The real LLM is never called in automated tests.** No LLM API keys are stored as CI secrets.
- The full end-to-end test (`docker compose up`, healthy stack, one process instance completing) runs
  **manually or nightly**, not on every pull request, because of the stack's footprint.
- Coverage is observed, not used as a gate.

## Consequences

### Positive

- Deterministic, fast feedback; no external cost or rate limits in CI.

### Negative

- Agent behaviour with the real model is validated only by end-to-end runs and manual demos.
