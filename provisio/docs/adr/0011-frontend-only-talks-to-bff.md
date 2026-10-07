# 0011 — Frontend talks only to the backend-for-frontend

- **Status:** Accepted
- **Date:** 2026-10-06

## Decision outcome

The React frontend never calls Camunda, MongoDB or the forecasting service directly. All calls go through
the Spring BFF, which shapes data for the UI and hides engine APIs and credentials.

## Consequences

### Positive

- Engine and data APIs can change without touching the UI; no credentials in the browser.

### Negative

- Every UI need requires a BFF endpoint.
