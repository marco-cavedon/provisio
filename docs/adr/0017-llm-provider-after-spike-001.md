# 0017 — LLM provider and configuration after spike 001

- **Status:** Accepted — supersedes [ADR 0008](0008-provider-agnostic-llm-on-free-tiers.md)
- **Date:** 2026-10-07

## Context and problem statement

[ADR 0008](0008-provider-agnostic-llm-on-free-tiers.md) chose the Gemini free tier for the agent.
[Spike 001](../spikes/001-llm-provider-and-ai-agent-connector.md) confirmed the architecture but showed that
Gemini's free quota (20 requests per day on the author's account) cannot sustain even development,
while Groq's free tier handled ten concurrent agent runs without errors.

## Decision outcome

- **Development provider: Groq free tier**, model `openai/gpt-oss-120b`, through the AI Agent connector's
  OpenAI-compatible provider.
- **Provider independence is kept**: endpoint, model and key come from `.env`; switching provider never touches
  BPMN, workers or Compose.
- **Only credentials are secrets.** The API key is passed to the connectors runtime as `SECRET_LLM_API_KEY`,
  mapped explicitly in Compose (least privilege: the connectors container receives only what it needs).
  Model and endpoint are ordinary configuration, so they stay visible in logs and errors.
- **Retries outlast the provider's rate-limit window**: retry backoff of at least 60 seconds on the agent element.
- **Embeddings**: Voyage AI free tier, unchanged from ADR 0008, to be validated when RAG is built.
- **Demo strategy: open.** Options are the development provider, or a paid provider with a hard spending cap used
  only for public demos. To be decided before the first public demo.

## Consequences

### Positive

- Development is no longer quota-bound; the provider switch validated the provider-agnostic design in practice.

### Negative

- Groq's free tier caps tokens per minute; RAG context will make token volume the next limit.
- Free-tier data-usage terms must be verified for Groq; only synthetic or public data may be sent, as before.
- Free tiers change without notice: the provider choice must be re-checked periodically.
