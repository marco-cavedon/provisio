# 0009 — Monorepo

- **Status:** Accepted
- **Date:** 2026-10-06

## Decision outcome

All components (processes, Spring application, Python service, frontend, seed, infrastructure, docs)
live in a single repository. BPMN and DMN models live in a top-level `processes/` folder rather than
inside the Spring resources: for this project the process model is a first-class artefact.

## Consequences

### Positive

- One place to read the whole system; atomic changes across components; simpler for reviewers.

### Negative

- Per-component CI pipelines need path filters.
