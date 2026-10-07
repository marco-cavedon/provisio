# Spike 001 — LLM provider and AI Agent connector

- **Status:** Completed — 2026-10-07
- **Validates:** [ADR 0004](../adr/0004-native-camunda-agentic-orchestration.md), [ADR 0008](../adr/0008-provider-agnostic-llm-on-free-tiers.md)
- **Outcome:** ADR 0004 confirmed; ADR 0008 superseded by [ADR 0017](../adr/0017-llm-provider-after-spike-001.md)
- **Code:** branch `spike/001-ai-agent-connector`, folder `spikes/001-ai-agent-connector` (never merged)

## Hypothesis

The Camunda AI Agent connector, configured with the OpenAI-compatible provider pointing at a free-tier LLM,
can run an agent loop inside an ad-hoc sub-process and call a tool implemented as a Java job worker,
on Camunda 8 Self-Managed running in Docker Compose.

## Setup

- Camunda **8.9.23** (orchestration) and connectors **8.9.14**, from the official lightweight Compose distribution.
- One BPMN: start → AI Agent ad-hoc sub-process (element template `aiagent.jobworker.v1`, version 8) with one tool
  (`GetStockLevel`) → end.
- One Spring Boot 4 worker using `camunda-spring-boot-starter` 8.9.23; it deploys the BPMN at startup.
- LLM through the connector's OpenAI-compatible provider: first `gemini-2.5-flash` (Gemini free tier),
  then `openai/gpt-oss-120b` (Groq free tier).

## Results

| Experiment | Gemini 2.5 Flash (free) | Groq gpt-oss-120b (free) |
| --- | --- | --- |
| Happy path: two SKUs, one below its reorder point | Pass | Pass |
| Unknown SKUs: no invented quantities | Pass | Pass |
| 10 concurrent instances | 3 completed, 7 incidents (HTTP 429) | 10 completed, no incidents |

Observations on the happy path (Gemini run):

- The model requested **both tool calls in a single turn**; the ad-hoc sub-process executed them in parallel.
  The whole answer took **2 model calls**, roughly 400 input and 110 output tokens, about 2 seconds end to end.
- `fromAi()` extracted the tool arguments correctly from the user's question.
- The **full conversation** (system prompt, question, tool calls with arguments, tool results, final answer) is stored
  in the `agent.context` process variable and inspectable in Operate: every agent decision can be reconstructed.

## Findings

### Provider

- **Gemini free tier is unusable for this project**: 5 requests per minute and **20 requests per day** for
  `gemini-2.5-flash` on the author's account. An agent run needs at least 2 requests, so the daily quota allows
  about 10 runs — not enough even for development.
- **Groq free tier works** for development and small demos at the current prompt size.
- **Provider independence paid off**: switching provider changed three environment variables; no change to BPMN,
  worker or Compose.
- **Rate limits are handled safely by the engine**: a 429 becomes a failed job, then retries, then an incident with
  a clear message, recoverable from Operate. Nothing is lost silently. With a 10-second backoff and 3 retries the
  retries all fall inside the provider's one-minute window, so backoff must exceed it.

### Integration friction (each one would hit anyone cloning the repository)

1. **Wrong JDK picked up by Maven** produced a cryptic "class file has wrong version" error.
   → Enforce the Java version with `maven-enforcer-plugin` and pin it with a `mise.toml`.
2. **Dependency conflict**: Spring Boot 4.0.8 manages Apache HttpClient 5.5.2, but the Camunda 8.9.23 client needs
   5.6.4 (`NoSuchMethodError` at startup). → Override `httpclient5.version` / `httpcore5.version`, documented, and
   re-checked on every Camunda or Spring Boot upgrade.
3. **Connector secrets require the `SECRET_` prefix** on environment variables by default (without it every
   environment variable would be exposed to process models). → Map only the needed variables, explicitly, in Compose.
4. **Job worker classes must be `public`**: the starter invokes worker methods reflectively from another package.
   A unit test in the same package would not catch it. → Process tests exercise workers through the real invocation path.

### Design

- **Tools return domain variables**; the BPMN output mapping turns them into `toolCallResult`. Workers stay unaware of
  the agent and reusable by non-agentic processes.
- **Tool error responses are part of the tool contract**: the dummy tool leaked the whole SKU list in its "not found"
  message and the agent relayed it. Business outcomes (not found, no availability) are returned as minimal structured
  data; technical failures fail the job so the engine retries.
- **Only credentials are secrets**: the runtime masks secret values in logs, so declaring the model name as a secret
  hid it from error messages. Model and endpoint are ordinary configuration.
- **Conversation stored in process variables** is great for auditability but will not scale to long conversations or
  sensitive data; the connector's document-based memory is the alternative to evaluate later.

### Environment

- Camunda **8.10** Compose files still reference snapshot images; **8.9** is the latest stable minor.
- The 8.9 lightweight stack uses **H2** as secondary storage: no Elasticsearch needed.
- `camunda-spring-boot-starter` 8.9 targets **Spring Boot 4**.

### Open points

- One `POST /v2/process-instances` with `awaitCompletion` returned **HTTP 503** while the instance was created;
  the API specification documents 504 for await timeouts. Not reproduced; to be watched.
- Groq's free tier allows about **8,000 tokens per minute**: with RAG context in prompts, token volume, not request
  count, is the next limit.
- Groq's data-usage terms for the free tier are to be verified.
- Memory footprint of the stack was not measured.

## Success criteria

- [x] The agent calls the tool at least once and uses its result in the final answer.
- [x] Tool calls and agent iterations are visible in the engine's tooling.
- [x] Behaviour under free-tier rate limits is observed and documented (errors, retries).
- [x] An API key can be obtained from Italy for the free tier.
- [ ] *(Additional)* Gemini 3 through the OpenAI-compatible layer — dropped: Gemini is no longer the development provider.
