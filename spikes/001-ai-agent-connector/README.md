# Spike 001 — runnable experiment

Throwaway code for [spike 001](../../docs/spikes/001-llm-provider-and-ai-agent-connector.md).
It lives only on the `spike/001-ai-agent-connector` branch and is never merged.

## What is here

| Path | Purpose |
| --- | --- |
| `docker-compose.yaml` | Camunda 8.9 Self-Managed, lightweight: orchestration (H2 secondary storage) + connectors |
| `configuration/application-h2.yaml` | Orchestration config from the official distribution (basic auth `demo`/`demo`, unprotected API) |
| `spike-worker/` | Spring Boot app: deploys the BPMN at startup and serves the `getStockLevel` tool |
| `spike-worker/src/main/resources/bpmn/spike-stock-agent.bpmn` | Start → AI Agent ad-hoc sub-process with one tool → End |

The LLM is configured only through the repository root `.env` (`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`),
exposed to the connectors container and referenced in the BPMN as `{{secrets.LLM_*}}`.

## Prerequisites

- Docker with Compose v2
- JDK 21
- Free ports: 8080, 8086, 9600, 26500
- Root `.env` filled in

## Run

```bash
# 1. Camunda (from this folder)
docker compose up -d
docker compose ps          # wait until orchestration and connectors are "healthy"

# 2. Worker (deploys the process and serves the tool)
cd spike-worker
./mvnw spring-boot:run     # keep it running; watch for the deployment log line

# 3. In another terminal: start an instance and wait for the agent's answer
curl -s -X POST http://localhost:8080/v2/process-instances \
  -H "Content-Type: application/json" \
  -d '{
        "processDefinitionId": "provisio-spike-stock-agent",
        "variables": { "question": "Check SKU-001 and SKU-002: which of them is below its reorder point?" },
        "awaitCompletion": true,
        "requestTimeout": 120000,
        "fetchVariables": ["agent"]
      }'
```

Expected: the worker logs one `getStockLevel` call per SKU, and the response contains `agent.responseText`
stating that SKU-001 (42 < 100) is below its reorder point and SKU-002 (350 ≥ 120) is not.

Inspect the instance in Operate: <http://localhost:8080/operate> (`demo` / `demo`).

## Experiments to run

1. **Happy path** — the request above.
2. **Unknown SKU** — ask about `SKU-999`: the agent should report it as unknown, not invent numbers.
3. **Rate limits** — start ~10 instances in a quick loop and observe 429 errors, retries (10 s backoff) and incidents.
4. **Gemini 3** — set `LLM_MODEL` to a Gemini 3 Flash model, `docker compose up -d connectors` to reload the
   variable, repeat 1: does the tool-calling loop still work through the OpenAI-compatible layer?

Record logs, screenshots and outcomes in the spike report.

## Troubleshooting

- Agent element fails → incident in Operate; details in `docker compose logs connectors`.
- Worker cannot connect → check that ports 26500 and 8080 are reachable on `localhost`.
- Clean restart → `docker compose down -v` (removes all process data).
