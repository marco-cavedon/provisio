# Roadmap

Where Provisio is going, milestone by milestone, and the requirements each milestone must satisfy.
Scope and architecture are defined by the [README](../README.md) and the [ADRs](adr/README.md); this page
orders the work and records requirements that are agreed but not yet designed. When a requirement turns
into an architectural decision, it gets an ADR and this page links to it.

Each milestone ends with a tag ([ADR 0018](adr/0018-trunk-based-development-and-tagged-releases.md)).
Milestones after v0.1.0 are a draft and will be refined as each one starts.

## Milestones

| Milestone | Goal | Requirements | Status |
| --- | --- | --- | --- |
| v0.1.0 — Foundations | Final Docker Compose infrastructure (Camunda, MongoDB, `mongot`), orchestrator skeleton, inventory module ([ADR 0022](adr/0022-inventory-data-model.md)) | — | Done |
| v0.1.1 — Public foundations | Repository public; published multi-platform images ([ADR 0024](adr/0024-image-publication-when-public.md)), quickstart without manual secrets, MongoDB on Linux 6.19+ kernels, security policy and dependency updates | — | Done |
| v0.2.0 — Demand and forecasting | Sales dataset and seed, forecasting service, nightly forecast batch ([ADR 0006](adr/0006-precomputed-forecast-with-on-demand-recompute.md)) | R1, R2, R3, R4 | Draft |
| v0.3.0 — Supplier knowledge | Synthetic supplier documents consistent with the dataset, retrieval over MongoDB Vector Search | R6 (retrieval references) | Draft |
| v0.4.0 — Agentic replenishment | Agent process with tools, DMN approval policy, planner approval in the frontend; orders issued to the simulated ERP; `LLM_API_KEY` required again in Compose | R5, R6, R9 (orders and answers), R10 | Draft |
| v0.5.0 — Demo-ready MVP | Supplier-delay re-evaluation, deliveries and simulated time, agent evaluation set, measured success indicators | R7, R8, R9 (deliveries, time, replay) | Draft |

## Requirements

Each requirement states what must be true, not how to build it. The delegation level follows
[`CLAUDE.md`](../CLAUDE.md); design-gate and owner-authored items are designed when their milestone starts.

### R1 — Forecasting method matches the demand pattern

- **Why:** a single method does not fit every SKU. Retail item-level sales are often intermittent
  (many zero days), where exponential smoothing performs poorly.
- **What:** SKUs are classified by demand pattern (for example by average inter-demand interval and
  squared coefficient of variation) and each class uses a suitable classical method: an intermittent-demand
  method such as Croston or its SBA variant, a seasonal method such as Holt-Winters for regular seasonal series.
  The class and the method are recorded with every forecast version.
- **Level:** 🔴 owner-authored (forecasting service). The choice of methods gets its own ADR, refining
  [ADR 0003](adr/0003-separate-forecasting-retrieval-and-agent.md).

### R2 — Forecast accuracy is measured against a baseline

- **Why:** a forecast is only defensible if it beats a naive alternative on data it has not seen.
- **What:** a backtest on a holdout period compares each method with a seasonal naive baseline, using
  metrics that remain valid with zero sales (for example MASE; not MAPE, which is undefined at zero).
  Results are reproducible and published with the milestone.
- **Level:** 🔴 owner-authored.

### R3 — Spike 002: LLM forecast versus statistical baseline

- **Why:** [ADR 0003](adr/0003-separate-forecasting-retrieval-and-agent.md) rejects LLM-generated forecasts
  on principle. A small experiment turns the principle into evidence.
- **What:** a time-boxed [spike](spikes/README.md) on a handful of SKUs compares LLM-produced forecasts with
  the R2 baseline on the same holdout, measuring accuracy and run-to-run variability. It runs outside the
  product and within the LLM provider's free-tier limits.
- **Level:** 🟡 design gate (spike plan), outcome recorded in the spike report.

### R4 — The sales dataset is chosen and its license verified

- **Why:** [ADR 0002](adr/0002-domain-and-mvp-scope.md) requires a public retail dataset but does not name it.
  Its license decides whether data can be committed to the repository, and its download method decides
  whether the demo still starts with a single `docker compose up`.
- **What:** a dataset is selected for a single product category, its license is verified for redistribution
  (or the seed downloads it without personal credentials), and the decision is recorded in an ADR.
  The M5 (Walmart) dataset is the leading candidate; its license has **not** been verified yet.
- **Level:** 🟡 design gate.

### R5 — Rejection is a first-class outcome

- **Why:** a governed agent is credible only if the demo shows a human overruling it.
- **What:** the planner can approve, amend or reject a proposal. Amendments and rejections carry a structured
  reason (a fixed list plus free text) stored with the proposal. The demo script includes at least one
  deliberate rejection.
- **Level:** 🟡 design gate (data model and user task contract); the BPMN part is 🔴.

### R6 — Every proposal has a complete audit record

- **Why:** a decision that cannot be reconstructed cannot be defended to an auditor or a customer.
- **What:** each purchase-order proposal references the forecast version it used
  ([ADR 0006](adr/0006-precomputed-forecast-with-on-demand-recompute.md)), the supplier document chunks
  retrieved and cited by the agent, the tool calls made (through the process instance), the DMN outcome,
  and the human decision with its reason.
- **Level:** 🟡 design gate (MongoDB schema).

### R7 — The agent is evaluated on a fixed scenario set

- **Why:** automated tests mock the LLM ([ADR 0014](adr/0014-testing-strategy.md)), so agent quality with
  the real model is otherwise judged on impressions. Errors become rarer and subtler as models improve,
  which makes systematic checking more important, not less.
- **What:** a versioned set of scenarios (around ten) with expected outcomes — supplier choice, quantity
  range, whether escalation is required — is run manually against the real model. The pass rate is recorded
  per prompt and model version.
- **Level:** 🟡 design gate.

### R8 — Success indicators are defined and measured

- **Why:** the product's value must be stated in business terms, not as a technology list.
- **What:** the demo replays a holdout period and reports, against a simple fixed reorder-point policy:
  stock-out days, average inventory held, share of proposals approved without changes, and share amended or
  rejected. Forecast accuracy comes from R2.
- **Level:** 🟡 design gate.

### R9 — The simulated ERP closes the loop

- **Why:** stock-out days and inventory held (R8) only exist if orders turn into deliveries, and a supplier delay is
  only credible once the order has been issued, as in real life.
- **What:** approved orders are issued to a simulated ERP outside the process, which answers asynchronously with
  confirmations, delays and deliveries, partial ones included. The answers look random but are reproducible: they
  come from a supplier-reliability calendar generated from a seed, so the same seed reproduces the same run, and
  Provisio and the baseline policy of R8 face the same world even though they issue different orders. Deliveries
  become inventory movements ([ADR 0022](adr/0022-inventory-data-model.md)). Simulated time can be advanced, so a
  past period is replayed in minutes. Switching supplier after an order was issued cancels the original order.
- **Time:** spike 003 decides how simulated time drives the process engine (for example the engine clock API, which
  is alpha) before the v0.5.0 design starts. Until then, the v0.4.0 process should not rely on engine timers for
  waits measured in days, or it may need rework.
- **Level:** 🟡 design gate (ERP contract, reliability calendar, simulated time); the BPMN part is 🔴.

### R10 — The agent can check a supplier before ordering

- **Why:** a planner does not order blindly from a supplier that is often late.
- **What:** the agent can read a supplier's historical reliability (late-delivery share, average delay) from a
  synthetic order history generated with the R9 seed and, when it is poor, ask the supplier for a lead-time estimate.
  The estimate can be wrong; it is recorded in the proposal's rationale and audit record (R6). The actual delay is
  only known after the order is issued (R9).
- **Level:** 🟡 design gate (tool contracts); the agent prompt and the BPMN part are 🔴.

## Before the repository goes public

The repository went public on 27 October 2026, after v0.1.0, once the following held:

- [x] The README status matches reality and states what does not exist yet.
- [x] The quickstart works from a clean clone, following only the documentation.
- [x] CI is green on `main`; the history passes secret scanning and the identity allow-list.
- [x] No data is committed whose license has not been verified (see R4).
- [x] Simplifications and known limits are stated ([README](../README.md#simplifications),
      [AI diligence statement](../AI-DILIGENCE.md#known-limits)).
- [x] Dependabot covers every ecosystem in the repository, with documented exceptions (Compose images, Camunda, and
      Spring Boot minor releases, upgraded together by hand: see `.github/dependabot.yml`), and a
      [security policy](../SECURITY.md) says how to report a vulnerability.
- [x] A ruleset protects `main`: pull requests only, required checks green and up to date, no force push, no deletion
      ([ADR 0015](adr/0015-ci-cd.md)). Before going public it was kept by discipline: rulesets are not available on a
      private repository of a free account.
- [x] Private vulnerability reporting is enabled.

Completed with v0.1.1, the first release that publishes images:

- [x] Docker images are published on version tags and the Compose file pulls them
      ([ADR 0024](adr/0024-image-publication-when-public.md)): `ghcr.io/marco-cavedon/provisio-orchestrator:0.1.1`,
      for linux/amd64 and linux/arm64, with provenance and SBOM attestations.
- [x] The container package is public: it pulls without logging in.
