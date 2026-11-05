# 0029 — Planned business calendar beyond the observed data

- **Status:** Accepted — amends the opening calendar of [ADR 0025](0025-sales-dataset-and-business-time.md) and
  the calendar model of [ADR 0026](0026-demand-module-and-seed-loading.md)
- **Date:** 2026-11-04

## Context and problem statement

The forecasting contract ([ADR 0028](0028-forecasting-contract-and-toolchain.md)) needs the open days after `asOf`:
lead time and review period are calendar days, demand is per open day, and the protection interval must lie within a
calendar that the request carries. The calendar of ADR 0025 is observed in the sales data: a day is open if a
transaction was recorded. It ends on 8 December 2011, the last full day of the dataset and the last day of the replay
period.

With a protection interval of about three weeks and a horizon of four, every `asOf` after about 10 November 2011 needs
days that the data cannot show. The last month of the replay, which includes the Christmas shutdown, could not be
forecast. A real retailer does not have this problem: it knows its opening schedule in advance.

## Decision drivers

- Replay must forecast every business date of its period, up to the last.
- Nothing invented may pass for data: a day the store has not lived yet carries no demand, not a zero.
- The rules of a planned calendar must come from the observed one, and be checked against it.
- The seed stays reproducible from the original archive, and a reload stays all or nothing (ADR 0026).

## Considered options

1. **Fail fast** when the calendar does not cover the horizon: the end of the replay cannot be forecast.
2. **Assume every day open** beyond the data: simple, and wrong on Saturdays and over Christmas, when demand is
   concentrated in fewer open days than the forecast would count.
3. **A planned calendar derived by rule** from the observed one, marked as planned.
4. **A hand-written list** of future closed days: no rule to test, nothing to show where the list comes from.

## Decision outcome

Option 3.

### The rules

The observed calendar has 135 closed days: Saturdays, UK bank holidays, and a Christmas shutdown. The planned
calendar keeps the two rules that recur in the observed one and checks them against it:

- **Closed on Saturdays.** The store traded on one Saturday out of 105 (5 December 2009).
- **Closed from 24 December to 3 January**, both included. In 2009–10 and 2010–11 every one of those days was closed,
  and 23 December and 4 January were open.

Bank holidays are not modelled: from 9 December 2011 to 31 January 2012 every one of them (26 and 27 December,
2 January) falls inside the shutdown. A longer planned range would need them.

The planned calendar runs from **9 December 2011 to 31 January 2012**: the last replay day plus the longest horizon in
use (28 days, to 5 January), with a margin for longer lead times. It has 54 days, 37 open.

### Seed

- `seed/derive` writes the planned calendar to its own file, `planned-calendar.csv`, with the same columns as
  `calendar.csv`, which does not change. The rules are in `rules.py`, next to the derivation rules, and are tested on
  fixtures and against the committed observed calendar: around both observed year ends, the rules give exactly the
  observed days.
- The manifest lists the file and counts its days in the summary (`plannedCalendarDays`, `plannedOpenDays`). A test
  rebuilds the manifest with the function `derive` uses and compares it byte for byte with the committed one.
- The seed module reads both files and refuses a planned calendar that does not start on the day after the observed
  one ends.

### Model

- `BusinessCalendar` gains **`observedTo`**, the last observed day: the observed part is a prefix, the planned part
  the rest. `isOpen` answers for both parts; `isObserved` tells them apart.
  `BusinessCalendar.observed(from, to, closedDays)` builds a calendar with no planned part.
- `business_calendar` keeps one document per year. A year that contains planned days records the first of them in
  **`plannedFrom`**; a year observed in full has no such field. Documents written before this change therefore read as
  fully observed, which is what they are; the reload that follows the new manifest replaces them anyway.
- **Planned days have no demand.** `DemandQueries.dailyDemand` refuses a range that reaches beyond `observedTo`
  instead of filling it with zeros, and `DemandImport.replaceAll` refuses demand on a planned day. Zeros on planned
  days would be invented sales, and a forecast fitted on them would be biased downwards.

## Consequences

### Positive

- Forecasting works for every business date of the replay, the Christmas shutdown included.
- The difference between "the store will be closed" and "nothing was sold" stays explicit, in the files, in MongoDB
  and in the API.
- The rules are checked against the data they come from.

### Negative

- The planned calendar is a model, not data: if the real store opened on a Saturday in December 2011, the forecast
  counts one open day too few.
- The planned range is fixed. A replay or horizon reaching beyond 31 January 2012 needs a longer range and, from Good
  Friday on, bank holidays.
- One more file in the seed, and one more field in the calendar documents.
