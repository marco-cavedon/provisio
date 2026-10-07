# 0005 — Python stateless forecasting service

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

Forecasting could be implemented in Java, keeping the stack uniform, or in Python, the de facto standard
for time-series work. In real organisations forecasting is often owned by a different team with different tools.

## Considered options

1. Forecasting in Java inside the Spring application.
2. Forecasting as a separate Python service called by the Java tool worker.
3. Forecasting as a Python job worker connected directly to Camunda.

## Decision outcome

Option 2:

- A **stateless** FastAPI service with a **contract-first** OpenAPI definition.
- It **does not know Camunda exists**: orchestration concerns stay in Java, where the Camunda client is most mature.
- Minimal, modern tooling: `uv`, FastAPI, Pydantic, an established statistics library, `pytest`.
- Classical methods only (no heavy ML): results must be explainable.

## Consequences

### Positive

- Clean boundary between numeric domain logic and orchestration.
- Realistic polyglot integration, typical of forward-deployed work.

### Negative

- One more runtime, container and toolchain to maintain.
