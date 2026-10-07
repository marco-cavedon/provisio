# 0001 — Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

Provisio is a showcase project: the reasoning behind the architecture matters as much as the code.
Decisions made in conversations are lost unless written down at the moment they are taken.

## Decision outcome

- Record every significant decision as an ADR in MADR format, in `docs/adr`, **when the decision is made**.
- ADRs are immutable once accepted; changes are captured by superseding ADRs, preserving the decision history.
- All documentation is written in **English**.
- Diagrams are written **as code** (Mermaid) inside Markdown, so they are versioned and rendered natively by GitHub.

## Consequences

### Positive

- The evolution of the architecture, including reversed decisions, is visible and reviewable.
- Diagrams never drift from an exported image.

### Negative

- Requires discipline: an ADR is part of the definition of done for any architectural change.
