# 0019 — Job workers live in their domain modules

- **Status:** Accepted — refines the module candidates of [ADR 0016](0016-spring-modulith.md)
- **Date:** 2026-10-08

## Context and problem statement

[ADR 0016](0016-spring-modulith.md) listed an `agent` module holding all tool adapters exposed to Camunda.
[Spike 001](../spikes/001-llm-provider-and-ai-agent-connector.md) established that job workers must not know they
serve an AI agent: a worker returns a domain variable and the BPMN maps it to `toolCallResult`, so the same worker can
serve non-agentic processes too.

A central `agent` module would contradict that principle in its very name, and would become a hub depending on every
other module.

## Considered options

1. A central `agent` module containing every job worker.
2. Each domain module owns the job workers for its capabilities.

## Decision outcome

Option 2.

- A job worker is an **entry point** into a module, like a REST controller: it adapts an engine job to the module's
  application service. It lives in an internal sub-package of its domain module, e.g. `inventory.worker`.
- Workers call the module's own services; they never reach into other modules' internals (verified by
  `ApplicationModules.verify()`).
- Job types are named after the domain capability, not after the agent: `provisio.inventory.get-stock-level`.
- The `agent` module is dropped from the candidate list. Agent-specific concerns (system prompts, tool selection,
  `toolCallResult` mapping) live in the BPMN models under `processes/`, which is where Camunda places them.
- The `web` module (BFF) is kept as a separate module: unlike a worker, which maps to a single domain capability,
  a BFF endpoint typically composes several modules into a view for the UI.

## Consequences

### Positive

- Each module is a cohesive vertical slice: a business change touches one module, worker included.
- Module boundaries follow the domain, so a future extraction of a module takes its workers with it.

### Negative

- Job worker conventions (public classes, domain result variables, error contract) must be applied consistently
  across modules; they are documented in the orchestrator README and enforced by process tests.
