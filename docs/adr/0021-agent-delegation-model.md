# 0021 — Delegation model for AI-assisted development

- **Status:** Accepted
- **Date:** 2026-10-21

## Context and problem statement

Provisio is built by one developer working with an AI coding agent (Claude Code). The project has two goals:
a credible, demoable product, and the developer's own growth as a senior engineer and solution architect.

An agent can produce most of this codebase. Left unchecked, that serves the first goal and defeats the second:
the parts most likely to be probed in a technical interview — agentic BPMN, retrieval, forecasting — would be
code the developer did not design. It also concentrates risk where mistakes are hardest to undo: data model,
module boundaries, git history, public releases.

We need an explicit rule for what the agent may do on its own, what needs a design check, and what the
developer writes himself, and a way to make that rule apply in every session.

## Decision drivers

- Interview defensibility: the developer must be able to explain and defend every core design choice.
- Reversibility: irreversible or public changes need a human decision.
- Throughput: low-value, low-risk work should not wait on line-by-line approval.
- Enforcement: the rule must be active in every agent session, not only when remembered.
- Transparency: visitors should see how the agent was governed.

## Considered options

1. **No explicit governance**: approve each request ad hoc in conversation.
2. **A skill only**: a Claude Code skill describing levels and procedure.
3. **`AGENTS.md`** as a cross-tool instruction file, plus a skill.
4. **`CLAUDE.md` for the rules, a skill for the procedure, labels and a pull request template for traceability.**

## Decision outcome

Option 4.

Every change is assigned one of three levels, carried as a pull request label:

| Level | Label | Who writes | Developer's check |
| --- | --- | --- | --- |
| Delegated | `delegated` | Agent | Pull request review |
| Design gate | `design-gate` | Agent, after the design is approved | Design approval, then review |
| Owner-authored | `owner-authored` | Developer | Agent reviews and pairs |

A level is chosen by three questions, in order: will it be asked in an interview, is it hard to reverse,
is it public under the developer's name. The default mapping lives in [`CLAUDE.md`](../../CLAUDE.md).

Approval is given **on the scope of each pull request** (what will change, what will not, side effects),
not on individual lines: control is exercised at scope approval and at review.

- `CLAUDE.md` holds the rules. Claude Code loads it in every session, so the rules cannot be skipped.
- The `provisio-pr-workflow` skill holds the step-by-step procedure, loaded when a change is requested.
- The pull request template and labels make the level of every change visible in the project history.

Option 1 was rejected: the rule lives only in conversation memory and erodes. Option 2 was rejected:
a skill is loaded when its description matches the task, so an always-on rule could silently not apply.
Option 3 was rejected: Claude Code is the only agent used on this project, so a cross-tool file adds an
indirection with no benefit. It can be revisited if another agent is adopted.

## Consequences

### Positive

- The developer owns the parts that matter most for the project's purpose; the agent accelerates the rest.
- Scope approval surfaces misunderstandings before code exists, when they are cheapest to fix.
- The labels give an honest, auditable record of how human and agent shared the work.

### Negative

- A proposal round-trip before every change slows small fixes.
- Level assignment is a judgment call; borderline cases default to the stricter level, which costs time.
- Labels are applied by hand and are not enforced by CI.
