# 0008 — Provider-agnostic LLM and embeddings on free tiers

- **Status:** Accepted, pending validation by [spike 001](../spikes/001-llm-provider-and-ai-agent-connector.md)
- **Date:** 2026-10-06

## Context and problem statement

The project must run at zero cost. Two models are needed: a chat model with reliable tool calling
for the agent, and an embedding model for RAG. They need not come from the same provider.

## Considered options

1. Paid cloud provider (pay-as-you-go).
2. Free tier of a cloud provider.
3. Local model (e.g. Ollama) in Docker Compose.

## Decision outcome

- **Agent LLM:** Gemini free tier, accessed through the AI Agent connector's **OpenAI-compatible** provider
  (Vertex AI would require a billed Google Cloud account).
- **Embeddings:** Voyage AI free tier — the embedding family promoted by MongoDB for Vector Search.
- **Provider-agnostic by configuration:** model, endpoint and keys come from environment variables;
  switching to a paid provider is a configuration change.
- A local model may be added later as an optional Compose profile, never as the default.

## Consequences

### Positive

- Zero running cost.

### Negative

- **Rate limits:** an agent makes several LLM calls per process instance. Mitigations: retry with backoff;
  automated process tests use a **mocked** LLM and never depend on the live model.
- **Data usage:** free-tier inputs and outputs may be used by the provider to improve its models.
  Acceptable only because all data is synthetic or public. **This choice must never be replicated with customer data.**
- Tool calling through an OpenAI-compatibility layer is a risk, hence spike 001.
