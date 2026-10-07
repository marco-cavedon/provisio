# 0010 — Single modular Spring Boot application

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

The Java side has two responsibilities: implementing the agent's tools as Camunda job workers,
and exposing an API to the frontend (backend-for-frontend).

## Considered options

1. Two separate services (workers, BFF).
2. One application with strictly separated packages.

## Decision outcome

Option 2. Packages are separated by responsibility (agent tools, BFF API, integrations) with no
cross-dependencies other than explicit interfaces, so a future split is a refactoring, not a rewrite.

## Consequences

### Positive

- Fewer containers, less configuration, no network hop between workers and BFF.

### Negative

- Workers and API scale together; acceptable for the MVP.
