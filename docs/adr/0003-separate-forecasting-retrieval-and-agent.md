# 0003 — Separate forecasting, retrieval and agent reasoning

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

An early idea was to use RAG for demand forecasting. RAG retrieves textual knowledge to ground an LLM;
it is not a forecasting technique. LLM-generated quantities are not reproducible, hard to validate
and hard to defend in front of a customer.

## Considered options

1. LLM/RAG-based forecasting.
2. Clear separation of responsibilities between statistics, retrieval and agent reasoning.

## Decision outcome

Option 2:

- **Forecasting** is deterministic and statistical (e.g. exponential smoothing, safety stock, reorder point).
- **RAG** retrieves unstructured supplier knowledge: contracts, SLAs, lead times, minimum order quantities, delay notices.
- **The agent** combines forecast, stock and supplier constraints to propose an order and explain it.
- **DMN** enforces policies; **humans** approve above defined thresholds.

## Consequences

### Positive

- Numbers are reproducible and testable; the agent only reasons on top of them.
- Clear story: deterministic where reliability matters, agentic where judgment matters, human where accountability matters.

### Negative

- More components than a single "ask the LLM" approach.
