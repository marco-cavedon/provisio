# 0016 — Spring Modulith and domain-oriented modules

- **Status:** Accepted — refines the package structure of [ADR 0010](0010-single-spring-boot-application.md)
- **Date:** 2026-10-06

## Context and problem statement

[ADR 0010](0010-single-spring-boot-application.md) chose a single Spring Boot application with strictly separated
packages. Nothing enforces that separation: one wrong import silently breaks it. ADR 0010 also suggested a split by
technical responsibility (agent tools, BFF, integrations), which tends to spread every business change across all packages.

## Considered options

1. Convention only (code review).
2. ArchUnit rules written by hand.
3. Spring Modulith.

## Decision outcome

Spring Modulith:

- **Modules by domain, not by technical layer.** Each direct sub-package of the application package is a module.
  Initial candidates, expected to evolve: `inventory`, `forecast`, `supplier` (knowledge and RAG), `purchasing`
  (proposals, approvals, ERP), `agent` (tool adapters exposed to Camunda), `web` (BFF API).
- **Boundaries verified in CI** by a test calling `ApplicationModules.verify()`: modules may only use each other's
  public API; internal sub-packages are hidden.
- **Module-level integration tests** (`@ApplicationModuleTest`) bootstrap one module at a time.
- **Events only where decoupling is real.** Direct calls through a module's API are the default; application events
  are used where a module must react without the publisher knowing it (e.g. an approved order triggering the ERP
  integration). If events must survive failures, the event publication registry is backed by MongoDB.
- **Generated documentation**: module diagrams produced by Spring Modulith's documenter complement the hand-written C4 diagrams.

## Consequences

### Positive

- "Modular monolith" becomes a verified property instead of an intention; a future split follows module boundaries.

### Negative

- New library for the author: learning cost, and a dependency whose version must track Spring Boot's.
- Risk of over-using events; mitigated by the "direct calls by default" rule.
