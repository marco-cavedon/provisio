---
name: provisio-pr-workflow
description: Use for any code, config or docs change to the Provisio repo — classify it by delegation level, propose the scope, wait for Marco's approval, then deliver one small pull request.
---

# Provisio pull request workflow

The rules live in [`CLAUDE.md`](../../../CLAUDE.md): role and discussion style, delegation levels, hard rules.
This skill is the procedure that applies them. If the two ever disagree, `CLAUDE.md` wins.

## 1. Classify

Assign the level using the mapping in `CLAUDE.md`. If the change spans levels, split it into separate pull
requests. If unsure, choose the stricter level and say why.

On 🔴 work, stop here: offer review, explanations and alternatives instead of an implementation.

## 2. Propose the scope, then stop

Before touching any file, send a proposal with:

- **Level** and the reason
- **Branch**: `feat/`, `fix/`, `docs/`, `ci/` or `chore/` plus a short slug
- **Will change**: files or areas
- **Will not touch**: explicit exclusions
- **Side effects** outside the repository (GitHub labels or settings, external accounts)
- **Open questions**, if any

On 🟡 work, include the design itself — schema, contract, diagram or draft ADR — and discuss it in depth:
assumptions, trade-offs, rejected alternatives, failure modes, risks.

Then wait for an explicit approval. Anything new discovered along the way goes back to this step.

## 3. Implement

- Branch from an up-to-date `main`.
- Check the commit identity before the first commit: `git config user.email` must be on the allow-list in
  `.github/scripts/check-commit-identities.sh`.
- Stay inside the approved scope; keep the change small and single-purpose.
- Run locally what CI will run on the touched areas (build, tests, Markdown lint, link check, `docker compose config`).

## 4. Open the pull request, then stop

- Fill [`.github/pull_request_template.md`](../../../.github/pull_request_template.md) and apply the level label.
- Link the related ADR or issue. State what was verified and how, and list any follow-ups.
- Never merge, tag or release. Report the link and wait for Marco's review.
