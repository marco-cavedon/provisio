<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/provisio-logo-dark.svg">
    <img src="docs/brand/provisio-logo.svg" alt="Provisio" height="64">
  </picture>
</h1>

[![Repository checks](https://github.com/marco-cavedon/provisio/actions/workflows/repo-checks.yml/badge.svg)](https://github.com/marco-cavedon/provisio/actions/workflows/repo-checks.yml)

**Agentic replenishment with governed AI decisions.**

Provisio is a showcase project that automates warehouse purchase-order decisions by combining
deterministic demand forecasting, retrieval over unstructured supplier knowledge, and an AI agent
orchestrated — and governed — by a BPMN process engine.

> **Status:** v0.1.0, foundations. The local stack starts with one command: Camunda, MongoDB with Search, and the
> orchestrator with the inventory module and its first agent tool. There is no end-to-end demo yet: forecasting,
> supplier knowledge and the agent process come next. See the [roadmap](docs/roadmap.md).

## The problem

Replenishment decisions depend on two very different kinds of information:

- **Structured, numeric data** — sales history, stock levels, reorder points. This is where
  statistics beat language models: results must be reproducible and explainable.
- **Unstructured knowledge** — supplier contracts, SLAs, negotiated lead times, minimum order
  quantities, delay notifications, incident notes. It usually lives in PDFs and inboxes, not in the ERP.

Planners spend their time reconciling the two. Provisio lets an AI agent do the reconciliation,
while the process engine keeps every decision bounded, auditable and — above a threshold — approved by a human.

## Design principle

> **Deterministic where reliability matters. Agentic where judgment matters. Human where accountability matters.**

| Concern | Handled by |
| --- | --- |
| Demand forecast, safety stock, reorder point | Statistical forecasting service (Python) |
| Supplier constraints, contracts, delays | Retrieval-augmented generation over MongoDB Vector Search |
| Choosing supplier and quantity, explaining why | AI agent inside a Camunda ad-hoc sub-process |
| Approval thresholds and policies | DMN decision tables |
| Final accountability | Human approval task |

## MVP scenario

1. A SKU drops below its reorder point, or the forecast predicts a stock-out.
2. A Camunda process starts. The AI agent gathers the forecast, current stock and supplier constraints (via RAG).
3. The agent proposes a purchase order (supplier, quantity, rationale).
4. A DMN table decides whether human approval is required.
5. A planner approves, amends or rejects the proposal, giving a reason for any change; approved orders are sent to a simulated ERP.
6. **Twist:** a supplier email announces a delay — the process re-evaluates and may switch supplier.

Every proposal keeps an audit record: the forecast version, the supplier documents the agent cited,
the policy outcome and the human decision.

## Success indicators

Planned, not yet measured. The demo will replay a past period and compare Provisio with a simple fixed
reorder-point policy on:

- stock-out days and average inventory held;
- share of proposals approved without changes, and share amended or rejected;
- forecast accuracy against a naive baseline.

Details are in the [roadmap](docs/roadmap.md).

## Architecture

See [`docs/architecture`](docs/architecture/README.md) for C4 context and container diagrams.

## Tech stack

| Layer | Technology |
| --- | --- |
| Process orchestration | Camunda 8 Self-Managed (AI Agent connector, ad-hoc sub-process, DMN) |
| Backend | Java, Spring Boot (tool workers + backend-for-frontend) |
| Forecasting | Python, FastAPI |
| Data & retrieval | MongoDB Community with Search / Vector Search (`mongot`) |
| Embeddings | Voyage AI |
| LLM | Provider-agnostic through an OpenAI-compatible API; Groq (free tier) in development ([ADR 0017](docs/adr/0017-llm-provider-after-spike-001.md)) |
| Frontend | React |
| Runtime | Docker Compose — one command to run the full demo |

## Repository layout

```text
processes/      BPMN, DMN and forms
orchestrator/   Spring Boot application: agent tools (job workers) + BFF API
forecasting/    Python forecasting service
frontend/       React UI
seed/           Datasets, synthetic supplier documents, initialisation jobs
infra/          Camunda, MongoDB and mongot configuration
docs/           ADRs, architecture diagrams, spike reports
```

## Quickstart

The local stack and the orchestrator run today. The orchestrator image is pulled from GitHub Container Registry, or
built from source when it is not available.

```bash
cp .env.example .env   # fill in the values
docker compose up -d
```

See [`infra/README.md`](infra/README.md) for requirements, verification and troubleshooting.

## Documentation

- [Roadmap](docs/roadmap.md) — milestones and planned requirements
- [Architecture Decision Records](docs/adr)
- [Architecture](docs/architecture/README.md)
- [Spikes](docs/spikes)
- [AI diligence statement](AI-DILIGENCE.md) — how AI is used to build this project, and the controls around it

## Configuration and secrets

Credentials live in a local `.env` file, which is git-ignored. `.env.example` is the committed template:
add a variable there (without a value) whenever a new one is needed.

## License

Provisio is licensed under the [Apache License 2.0](LICENSE).

### Third-party components

The demo runs third-party software distributed under its own licenses, pulled as Docker images
and not redistributed by this repository. In particular:

- **Camunda 8 Self-Managed** — free for development and non-production use; production use requires a Camunda license.
- **MongoDB Community Server** and **`mongot`** — Server Side Public License (SSPL).

Provisio is a showcase project and is not intended for production use.
