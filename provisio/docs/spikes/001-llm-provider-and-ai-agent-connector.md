# Spike 001 — LLM provider and AI Agent connector

- **Status:** Planned
- **Validates:** [ADR 0004](../adr/0004-native-camunda-agentic-orchestration.md), [ADR 0008](../adr/0008-provider-agnostic-llm-on-free-tiers.md)
- **Time-box:** 1–2 working sessions

## Hypothesis

The Camunda AI Agent connector, configured with the OpenAI-compatible provider pointing at the
Gemini free tier, can run an agent loop inside an ad-hoc sub-process and call a tool implemented
as a Java job worker, on Camunda 8 Self-Managed running in Docker Compose.

## Setup

- Minimal Camunda lightweight Compose stack.
- One BPMN: start → AI Agent (ad-hoc sub-process with one dummy tool, e.g. `getStockLevel`) → end.
- One Spring Boot job worker implementing the dummy tool.

## Success criteria

- [ ] The agent calls the tool at least once and uses its result in the final answer.
- [ ] Tool calls and agent iterations are visible in the engine's tooling.
- [ ] Behaviour under free-tier rate limits is observed and documented (errors, retries).
- [ ] An API key can be obtained from Italy for the free tier.

## Outcome

_To be filled in._ If the hypothesis fails, record the failure mode and the alternative
(another provider, or a custom agent loop) in a superseding ADR.
