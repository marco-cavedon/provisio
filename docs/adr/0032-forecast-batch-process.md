# 0032 — Forecast batch process

- **Status:** Accepted — refines [ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md): technical failures
  of the batch surface as incidents, business outcomes as end events
- **Date:** 2026-11-13

## Context and problem statement

The pieces of the forecast batch exist: two start events, a nightly timer and a message for replay and manual runs
([ADR 0025](0025-sales-dataset-and-business-time.md)); an idempotent, resumable batch worker
([ADR 0030](0030-forecasting-module-and-batch.md)); a business clock that gives the timer a date inside the data
([ADR 0031](0031-business-time.md)). The process model that puts them together had open questions: how each path
obtains `asOf`, whether the timer repeats a date, what happens when a run produces no usable forecast, how the models
reach the engine and how the model is tested.

The models are `processes/provisio-forecast-batch.bpmn` and `processes/provisio-forecast-run-assessment.dmn`,
authored by Marco (🔴).

## Decision drivers

- A run that produces no usable forecast must be visible, but it is a business outcome, not a technical failure.
- The nightly path must not recompute the same date every night; a manual run must always compute.
- Technical failures are handled once, by the engine, not in two places.
- The deployed models are the ones in the repository, deployed together.
- The model is tested as a model, independently of the workers.

## Decisions

### 1. Two start paths

- **Timer:** cron `0 0 1 * * *` (six fields, seconds first), every day at 01:00 UTC: engine timers are UTC, so 02:00 or
  03:00 in Italy depending on daylight saving time. The first task, *Get business date*
  (`provisio.time.get-business-date`), maps `businessDate` to `asOf` in its output mapping, so `asOf` is a process
  variable for every later task.
- **Message:** `provisio.forecasting.batch-requested`. The publisher passes `asOf` twice: as the correlation key and
  in the variables (the correlation key does not become a variable). A manual run uses a time to live of 0: it starts
  now or not at all, instead of waiting in the buffer.
- While an instance started for a date is active, a second request with the same correlation key starts no second
  instance; a test demonstrates it.

Rejected: a none start event with `createProcessInstance` (no deduplication); a signal start event (a broadcast, too
wide); an ISO 8601 cycle with a start date (a date in the model that ages).

### 2. The timer skips a date that already has a completed run

On the timer path only, *Find completed run* (`provisio.forecasting.find-completed-run`) looks for a `COMPLETED` run
of `asOf`, whatever its outcome; the gateway *Already forecast for asOf?* ends on *Already forecast* if there is one.
Runs still `RUNNING` do not count, so a run interrupted for good never blocks the timer. The message path goes
straight to the batch: recomputing a date that has a completed run is always explicit.

Checking and then starting is not atomic. Accepted: the timer is the only caller on that path, and a duplicate run
costs computation, not data (ADR 0030).

Rejected:

- **Skip if an instance for the date is running:** it does not skip a date already completed, so with a fixed business
  date the timer would recompute every night; and it depends on the `RUNNING` status, which an interrupted run keeps
  for good.
- **Skip on both paths:** a manual run is a request to compute.
- **The batch worker skipping on its own:** the decision would be hidden in the worker, the gateway decorative.
- **The business-date worker also returning whether the date is done:** two responsibilities, and the time module
  would read `forecast_runs`, owned by forecasting.

### 3. The outcome of a run is a decision, ending on one of three end events

*Assess forecast run*, a business rule task, evaluates the decision `provisio-forecast-run-assessment` on
`forecastRun`, with result variable `forecastRunAssessment`. The gateway *Forecast run usable?* ends on *Forecasts
available*, *Forecasts degraded* or, by default, *Forecasts unusable*: an unexpected outcome ends on the most
cautious one.

The decision table, hit policy UNIQUE:

| `requested` | failure rate (`failed / requested`) | `outcome` |
| --- | --- | --- |
| `0` | any | `unusable` |
| `> 0` | `<= 0.1` | `healthy` |
| `> 0` | `]0.1..0.5]` | `degraded` |
| `> 0` | `> 0.5` | `unusable` |

- `requested = 0` is a rule of its own: the rate is then `null` in FEEL, and no products almost always means a seed or
  configuration error.
- A second output, `reason`, says why in words an operator reads in Operate, with the counts of the run.
- **The thresholds are provisional** until the R2 backtest gives numbers.
- The task binds the decision with `bindingType="deployment"`: the decision deployed with this version of the process,
  never a later one deployed on its own.
- **A business outcome never raises an incident.** `latestForecast` already returns `FAILED` versions, so a reader
  sees "no number, and why"; the process makes the run's quality visible, it does not protect the reader.

Rejected: a single end event (a silent failure); an error end event or a forced incident (a technical mechanism for a
business outcome: Operate would show an error where there is a poor forecast); thresholds in the gateway conditions
(a business rule hidden in sequence flows); a user task to review the run (nobody to assign it to before the
frontend).

### 4. Technical failures stay incidents, not modelled

The batch worker's own error handling (ADR 0030) is the only one: no boundary error events and no retry loops in the
model. Service tasks keep the default of 3 retries. An incident is resolved by fixing the cause and retrying in
Operate; the batch resumes the same run.

### 5. The orchestrator deploys the models at startup

Every `*.bpmn` and `*.dmn` file of `processes/` ([ADR 0009](0009-monorepo.md)) goes in a single deployment when the
orchestrator starts: the build packages the folder on the classpath and in the image.

- One deployment, because the decision binding requires the decision in the same deployment as the process; deploying
  the process alone is rejected by the engine.
- Unchanged files create no new version; the startup fails if no model is found, rather than running without
  processes.
- The timer is armed whenever the orchestrator runs, and a manual deployment is overwritten at the next restart: the
  repository is the source of truth.
- Tests disable the startup deployment and deploy what they need.

Rejected: an init container calling `POST /v2/deployments` (one more moving part, and models and workers would be
versioned apart); deploying by hand from Desktop Modeler (not reproducible, and one file at a time).

### 6. The model is tested as a model

`ForecastBatchProcessTests` runs the product models with the real decision table and every job type mocked; the
workers have their own tests, and their contract tests use test-only processes, never the product models. Elements
are selected by name, the names an operator reads. The tests verify:

- the timer path in order, and that *Find completed run* and *Run forecast batch* receive `asOf` from the process
  scope, as set by *Get business date*;
- the skip on a completed run;
- that the message path never looks for a completed run;
- one instance per correlation key while active;
- every rule of the decision and its boundaries (0.1 is `healthy`, 0.5 is `degraded`), with the exact `reason`.

### 7. Names

- A model file is named after the id of its process or decision, with the `provisio-` prefix of the job types.
- The id that a business rule task references is the id of the `decision` element, not of the `definitions`.
- Every event, task and deciding gateway has a name: it is what Operate shows and what the tests select. Element
  ids stay as the Modeler generated them.

## Consequences

### Positive

- Each night produces at most one run per business date, and every run ends on an end event that says how usable it
  is, with the reason and the counts.
- Manual runs and replay always compute, and concurrent requests for a date do not duplicate the work.
- Technical and business failures are told apart: an incident means something to fix, an end event means something
  to read.
- The deployed models are the reviewed ones, deployed together.

### Negative

- Until R1, every run ends on *Forecasts unusable*; and the timer never repeats a date, even once R1 exists:
  recomputing a date already completed is a manual run.
- The thresholds of the decision are invented until R2.
- Renaming an element breaks the tests, deliberately: they select elements by the names an operator reads.
- With the local stack up at 01:00 UTC, the batch runs on its own.
- BPMN and DMN cannot be deployed separately, by hand or otherwise.
