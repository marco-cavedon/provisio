# 0004 — Native Camunda agentic orchestration

- **Status:** Accepted, validated by [spike 001](../spikes/001-llm-provider-and-ai-agent-connector.md)
- **Date:** 2026-10-06

## Context and problem statement

The agent loop can live either in Camunda (AI Agent connector with an ad-hoc sub-process exposing tools)
or in custom code (a Spring job worker using an agent framework).

## Considered options

1. Camunda AI Agent connector + ad-hoc sub-process; tools implemented as Java job workers.
2. Custom agent loop in Spring (e.g. Spring AI or LangChain4j) invoked as a single service task.

## Decision outcome

Option 1. The agent's tool calls become visible, auditable process activity, and governance
(DMN, human tasks, escalation) wraps the agent natively. Tools are implemented in Java, so the
project still contains meaningful backend code.

## Consequences

### Positive

- Agent behaviour is observable in the process engine's tooling and history.
- Showcases the platform's agentic capabilities as intended by the vendor.

### Negative

- Less control over the agent loop (context handling, memory, retry strategy).
  Limitations met during development will be documented, together with the workarounds adopted.
