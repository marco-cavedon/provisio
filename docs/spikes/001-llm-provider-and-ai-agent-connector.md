# Spike 001 — LLM provider and AI Agent connector

- **Status:** In progress
- **Validates:** [ADR 0004](../adr/0004-native-camunda-agentic-orchestration.md), [ADR 0008](../adr/0008-provider-agnostic-llm-on-free-tiers.md)
- **Time-box:** 1–2 working sessions
- **Code:** branch `spike/001-ai-agent-connector`, folder `spikes/001-ai-agent-connector` (never merged)

## Hypothesis

The Camunda AI Agent connector, configured with the OpenAI-compatible provider pointing at the
Gemini free tier, can run an agent loop inside an ad-hoc sub-process and call a tool implemented
as a Java job worker, on Camunda 8 Self-Managed running in Docker Compose.

## Setup

- Camunda **8.9.23** (orchestration) and connectors **8.9.14**, from the official lightweight Compose distribution.
- One BPMN: start → AI Agent ad-hoc sub-process (element template `aiagent.jobworker.v1`, version 8) with one tool
  (`getStockLevel`) → end.
- One Spring Boot 4 worker using `camunda-spring-boot-starter` 8.9.23; it deploys the BPMN at startup.
- LLM: `gemini-2.5-flash` through Gemini's OpenAI-compatible endpoint; credentials only in `.env`,
  referenced as `{{secrets.LLM_*}}`.

## Findings during preparation

- **8.10 is not yet stable**: the official Compose files for 8.10 still point to snapshot images, so the spike
  targets 8.9, the latest stable minor.
- **The lightweight stack no longer needs Elasticsearch**: in 8.9 the lightweight distribution uses an RDBMS
  (H2 by default) as secondary storage. The footprint concern in [ADR 0007](../adr/0007-local-runtime-with-docker-compose.md)
  is smaller than expected; to be measured.
- **Spring Boot 4**: in 8.9 `camunda-spring-boot-starter` targets Spring Boot 4.x
  (`camunda-spring-boot-3-starter` exists for 3.5.x). The orchestrator will start on Boot 4.
- **Tools decoupled from the agent**: the worker returns a domain variable (`stockLevel`); the BPMN output mapping
  turns it into `toolCallResult`, the convention the ad-hoc sub-process collects. Workers stay reusable by
  non-agentic processes. The BPMN passes Camunda's 8.9 compatibility lint rules.

## Success criteria

- [ ] The agent calls the tool at least once and uses its result in the final answer.
- [ ] Tool calls and agent iterations are visible in the engine's tooling.
- [ ] Behaviour under free-tier rate limits is observed and documented (errors, retries).
- [x] An API key can be obtained from Italy for the free tier.
- [ ] *(Additional)* Behaviour with a Gemini 3 model through the OpenAI-compatible layer is documented.

## Outcome

*To be filled in.* If the hypothesis fails, record the failure mode and the alternative
(another provider, or a custom agent loop) in a superseding ADR.
