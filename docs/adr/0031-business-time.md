# 0031 — Business time from a configurable business clock

- **Status:** Accepted — specifies how the timer path of the forecast batch obtains `asOf`, left open by
  [ADR 0025](0025-sales-dataset-and-business-time.md)
- **Date:** 2026-11-10

## Context and problem statement

ADR 0025 gives the forecast batch process two start events: a nightly timer for the live demo, and a message start
event for replay and manual runs. On the message path the caller supplies `asOf`; on the timer path "the process sets
it from the business clock". No business clock exists yet: spike 003 decides in v0.5.0 how simulated time drives the
engine (roadmap R9).

The timer fires on the engine's wall clock, in 2026. The data ends on 8 December 2011, and the batch rejects an `asOf`
more than one day after the last observed day ([ADR 0030](0030-forecasting-module-and-batch.md)). Whatever sets `asOf`
on the timer path must therefore produce a date inside the data, every night, without anyone present.

The same question will come back in v0.4.0: the agent process, inventory movements and the simulated ERP all need to
know which business day it is.

## Decision drivers

- The timer path must produce a valid business date every night, or it raises an incident every night.
- Reproducible: the same night, run again, gives the same date; a test can pin it.
- A demo must be able to choose its date, for example the run-up to the Christmas shutdown.
- Replaceable by spike 003 without changing the process model.
- One owner for business time, verified by Spring Modulith ([ADR 0016](0016-spring-modulith.md)), usable by every
  module that will need it.

## Considered options

### Source of `asOf` on the timer path

1. **`today()` in a FEEL expression.** A 2026 date: an incident every night.
2. **A fixed date in the BPMN model.** Business data inside the model; changing it means a new deployment.
3. **The day after the last completed run**, read from `forecast_runs`. A slow replay, one business day per night:
   implicit state, it anticipates spike 003, and it runs out after 8 December 2011.
4. **No timer until a business clock exists.** Honest, but it contradicts ADR 0025 and removes the nightly path.
5. **The current date moved into the data years, with a random fallback** when it falls after the data. Not
   reproducible, and business time would jump backwards several nights in a row.
6. **A business clock in the application**, read by a service task after the timer, with two configured modes:
   the current date moved into the data years by a deterministic rule, or a fixed date.

### Where the business clock lives

1. **The root package**, next to `ClockConfiguration`. Simplest, but Spring Modulith verifies nothing there, and the
   root package becomes where everything shared ends up.
2. **The `demand` module**, which owns the calendar and knows where the data ends. The wrong owner: inventory would
   depend on sales history to know the date.
3. **A new `time` module** that depends on no other module.

## Decision outcome

Source option 6, location option 3.

### The `time` module

- Public API: `BusinessClock`, with a single method, `LocalDate today()`. Advancing time, or announcing that the day
  changed, is left to spike 003: adding it now would be guessing.
- Internal: the implementation and `BusinessTimeProperties`. The module depends on no other module; any module may
  depend on it.
- Job worker `provisio.time.get-business-date` in `time.worker` ([ADR 0019](0019-job-workers-live-in-domain-modules.md)):
  no input; output `businessDate`, an ISO-8601 date. The variable is named after what it is, not after its first
  consumer: the forecast batch process maps it to `asOf` in its own output mapping, and the agent process of v0.4.0
  can use the same task.
- The wall-clock `java.time.Clock` of `ClockConfiguration` stays as it is, for timestamps such as `recordedAt` and
  `computedAt`.

### Two modes

`provisio.business-time.mode` selects one implementation's behaviour:

- **`mapped`** (the default): the current UTC date, the same time zone as engine timers, with its year replaced by
  `mapped.year` (2011). If that date falls after `mapped.latest` (9 December 2011, the first day the batch accepts
  after the data), the year before is used instead: 10 to 31 December map to 2010. Every result has at least a year
  of history. 29 February maps to 28 February, so two consecutive nights share a date.
- **`fixed`**: always `fixed-date`. For a demo of a chosen scenario, or to reproduce a run.

Startup fails if `fixed` has no date, if a fixed date is configured while the mode is `mapped` (it would be silently
ignored), or if `mapped.latest` is not in `mapped.year`.

The rule does not read the calendar from the `demand` module: that would make `time` depend on it. The limit is
configuration, written in two places (the data and `mapped.latest`); a mismatch is not silent, because the batch
rejects a date outside the data with an incident.

### Business time is read once per process instance

A process reads the business date once, at its start, and carries it as a variable; workers receive it as input and
never ask the clock themselves. A process that waits for hours or days (approvals in v0.4.0, deliveries in v0.5.0)
would otherwise see two different business dates, and with the mapped mode possibly a date a year earlier. The forecast
batch already follows the rule: `asOf` is a variable and the batch worker never reads a clock.

## Consequences

### Positive

- The timer path produces a valid date every night with no state and no randomness; a test pins it with a fixed
  `Clock`.
- A demo chooses its scenario with one setting; a reproduction uses the same setting.
- Spike 003 replaces the implementation behind `BusinessClock`; the process model and its workers do not change.
- Business time has one owner, and the rule of one read per instance is stated before the long-running processes exist.

### Negative

- **The mapped mode is not monotonic:** once a year business time jumps back from 9 December 2011 to 10 December 2010.
  Harmless while the forecast batch is its only reader; it must be replaced by spike 003 before orders, deliveries
  and inventory movements carry business dates (v0.4.0–v0.5.0).
- Nightly runs in the mapped mode follow the calendar of the day the demo runs, not the story a demo wants to tell:
  the fixed mode is the answer, and someone has to remember to set it.
- The end of the data is configuration as well as data.
- A service task more on the timer path of the forecast batch process.
