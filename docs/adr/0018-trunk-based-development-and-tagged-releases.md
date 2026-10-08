# 0018 — Trunk-based development and tagged releases

- **Status:** Accepted
- **Date:** 2026-10-08

## Context and problem statement

Visitors of the repository should see clear deliverables rather than a continuous stream of work in progress.
A long-lived `dev` branch merged into `main` at each deliverable (Git Flow style) was considered.

## Considered options

1. Long-lived `dev` branch; `main` updated only at each deliverable.
2. Trunk-based development: short-lived branches merged into `main`; deliverables marked by tags and releases.

## Decision outcome

Option 2.

- **Short-lived branches** (`feat/…`, `fix/…`, `docs/…`, `ci/…`) merged into `main` through pull requests, with required
  checks and squash merge. `main` is always integrated, tested and green: it is not "work in progress".
- **A deliverable is a tag, not a branch.** Each completed milestone gets a semantic version tag (`v0.1.0`, `v0.2.0`, …)
  and a **GitHub Release** with notes describing what can be demoed.
- **Versioning:** `0.x` while the MVP is incomplete; `1.0.0` marks the first demo-ready, end-to-end MVP.
- Tags trigger the publication of Docker images ([ADR 0015](0015-ci-cd.md)), so a release is also runnable.
- Spike branches remain the exception: never merged, kept as references.

Option 1 was rejected: with a single developer and no staging environment it doubles pull requests and checks,
requires keeping two branches aligned, and leaves `main` weeks behind the actual state of the project.

## Consequences

### Positive

- One integration branch; the Releases page tells the project's story milestone by milestone.
- Matches the practice associated with high-performing teams in DORA research.

### Negative

- Discipline required: `main` must stay demoable, so incomplete features must not break existing flows.
