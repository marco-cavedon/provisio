# AI diligence statement

Provisio is built by one developer, Marco, working with an AI coding agent. This statement explains
how AI is used in the project, who is responsible for what, and which controls keep the result
trustworthy. It summarises and links to the documents that define the rules; where they differ,
those documents prevail.

## Two different uses of AI

AI appears in this project in two roles that are easy to confuse:

- **Building the project.** Claude Code, Anthropic's coding agent, drafts code, configuration and
  documentation under the rules below.
- **Inside the product.** At runtime, an AI agent orchestrated by Camunda proposes purchase orders, using an
  LLM reached through a provider-agnostic, OpenAI-compatible interface (Groq's free tier in development) and
  embeddings from Voyage AI. See [ADR 0017](docs/adr/0017-llm-provider-after-spike-001.md).

The sections below cover the first role; the last section covers the second.

## Who did what

Every change is assigned a delegation level before any code is written, and each pull request carries it
as a label:

- `delegated`: written by the agent, reviewed by Marco — infrastructure, CI, test scaffolding, the frontend.
- `design-gate`: the agent writes it only after Marco approves the design — data model, module
  boundaries, contracts, agent guardrails, dependencies, anything public.
- `owner-authored`: written by Marco, with the agent as reviewer — the agentic BPMN processes, the
  retrieval pipeline, the forecasting service.

The core of the project is owner-authored on purpose: it is the part a technical reviewer is most likely to
probe, and Marco must be able to explain and defend it. The full mapping and the rules the agent follows
are in [`CLAUDE.md`](CLAUDE.md); the reasoning is in [ADR 0021](docs/adr/0021-agent-delegation-model.md).
The labels make the split auditable: filtering the closed pull requests by label shows who wrote what.

## Controls

- **Scope approval before code.** The agent proposes what it will change, what it will not touch and any
  side effects, then waits for an explicit approval.
- **Human review and merge.** Every change reaches `main` through a pull request that Marco reviews and
  merges. The agent never pushes to `main`, merges, tags or releases
  ([ADR 0018](docs/adr/0018-trunk-based-development-and-tagged-releases.md)).
- **Automated checks on pull requests** (the build runs when its area changes): secret scanning, an allow-list of commit identities, build and
  tests including Spring Modulith structure verification, Docker Compose validation, Markdown lint and
  internal link checking ([ADR 0015](docs/adr/0015-ci-cd.md), [ADR 0014](docs/adr/0014-testing-strategy.md)).
- **Decisions are written down.** Every architectural decision is recorded as an ADR when it is made
  ([ADR 0001](docs/adr/0001-record-architecture-decisions.md)), including those the agent proposed.
- **Verify before deciding.** Choices that depend on unverified product behaviour are tested first with a
  time-boxed [spike](docs/spikes/README.md), as with the LLM provider and the Camunda AI Agent connector.

## Data and secrets

- The project uses public sales data and synthetic supplier documents only. No customer, employer or
  personal data is used, in development or in the demo.
- This also means nothing confidential is sent to the external LLM and embedding providers. Their free-tier
  data-usage terms have not been verified yet ([ADR 0017](docs/adr/0017-llm-provider-after-spike-001.md)),
  so only public or synthetic data may ever be sent to them.
- Credentials live in a git-ignored `.env` file; the repository holds placeholders only.

## Known limits

- **One human reviewer.** There is no second pair of eyes; review quality depends on one person.
- **Labels are applied by hand.** CI does not verify that a pull request's label matches its content.
- **Agent output can be wrong.** Generated code and text are reviewed, not trusted; errors can still slip through.
- **Showcase, not production.** Provisio is not intended for production use (see the [README](README.md#license)).

## AI governance inside the product

The runtime agent is bounded by the process engine rather than trusted on its own:

- It reasons inside a Camunda ad-hoc sub-process and can only call the tools the process exposes.
- Numbers come from a deterministic forecasting service, not from the language model.
- A DMN decision table decides when a proposal needs human approval, and a planner approves or amends it.

The design principle, in the [README](README.md#design-principle): deterministic where reliability matters,
agentic where judgment matters, human where accountability matters.
