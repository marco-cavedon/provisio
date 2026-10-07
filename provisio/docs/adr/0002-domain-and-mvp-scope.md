# 0002 — Domain and MVP scope

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

The project must demonstrate agentic orchestration, RAG and process governance on a credible
business problem, not as a technology tutorial. The domain chosen is **warehouse replenishment**
(purchase-order decisions), outside the author's previous public-administration domain.

## Decision drivers

- A realistic problem with both structured and unstructured inputs.
- A scope small enough to be completed and demoed end to end.
- Limited domain expertise must be compensated by a narrow, well-defined scope.

## Decision outcome

**In scope (MVP):** a single warehouse, a single product category, a handful of suppliers;
one end-to-end replenishment process including human approval and a supplier-delay event.

**Out of scope:** multi-warehouse, transport logistics, production-grade authentication,
master-data management UI.

Data: a public retail sales dataset for demand history, plus synthetic supplier contracts,
SLAs and emails consistent with it.

## Consequences

### Positive

- A complete, demoable story in limited time.

### Negative

- Simplifications must be stated explicitly so the demo is not mistaken for a production design.
