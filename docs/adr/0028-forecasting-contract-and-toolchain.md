# 0028 — Forecasting contract and toolchain

- **Status:** Accepted
- **Date:** 2026-10-30

## Context and problem statement

[ADR 0005](0005-python-stateless-forecasting-service.md) and [ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md)
settle the shape of the forecasting service: stateless, contract-first, a bulk endpoint, models generated on both
sides, CI failing when code and contract diverge. They leave open what the contract says and how the "CI fails" part
works. Both have to be settled before the forecasting methods (roadmap R1) and the Spring `forecasting` module are
written against it.

The questions:

- Where the service stops: does it return order quantities, or only demand?
- What the caller must send so the service can turn a lead time into periods of demand.
- How a bulk call reports a product that cannot be forecast.
- Which OpenAPI version, and how CI proves that the service conforms to the contract.

## Decision drivers

- The service stays a pure function: input in, output out, no state, no database
  ([ADR 0006](0006-precomputed-forecast-with-on-demand-recompute.md)).
- Demand exists on open days only; a closed day is not a day of zero demand
  ([ADR 0025](0025-sales-dataset-and-business-time.md), [ADR 0026](0026-demand-module-and-seed-loading.md)).
- Every forecast is reproducible for any business date `asOf`, and a backtest cannot leak the future.
- The analysis of the dataset: almost every product is lumpy, so the normal approximation `z·σ·√LT` underestimates
  the demand to cover. Whatever estimates the quantile needs the model.
- The Java client must come from a generator in a stable state.
- Additive changes must not break the Java client: Jackson rejects unknown enum values by default.

## Considered options

### Where the service stops

1. **Order quantities.** The service receives stock on hand, on order, minimum order quantity and supplier terms, and
   returns how much to order.
2. **Demand only, including its quantile over the protection interval.** The caller turns that demand into an order.

### Protection interval

1. **Lead time only**, as a number of days.
2. **Lead time plus review period**, with the lead time as an estimate (mean and optional standard deviation).

### A product that cannot be forecast

1. **Fail the whole call.**
2. **Per-product outcome as `oneOf` with a discriminator** (`OkResult` | `FailedResult`).
3. **Per-product outcome as one flat object**: an `outcome` field says which of the optional fields are present.

### OpenAPI version

1. **3.1**, the version FastAPI emits.
2. **3.0.3.**

### Proving conformance in CI

1. **Compare the contract with `app.openapi()`** using oasdiff.
2. **Test the behaviour**: models regenerated and compared, routes compared with the contract's operations, and
   requests generated from the contract run against the app with Schemathesis.

## Decision outcome

### The contract

`contracts/forecasting/openapi.yaml`, at the repository root because it belongs to both sides. One operation,
`POST /v1/forecasts`, plus `GET /health`.

- **The service stops at demand** (option 2). It returns the forecast and the demand over the protection interval:
  mean and quantile at the requested service level, with the method that estimated the quantile. Subtracting stock
  on hand and on order, rounding to the minimum order quantity and applying the supplier terms retrieved in v0.3.0
  belong to the orchestrator and the agent. The quantile stays in Python because it is a statistical estimate that
  needs the model; an order quantity is a business decision. Option 1 would move inventory policy and supplier data
  into a service that should know neither.
- **Lead time plus review period** (option 2). Replenishment runs as a periodic batch, an (R, S) policy: an order
  placed today must last until the order placed at the next review arrives. The interval starts on `asOf` and lasts
  `ceil(leadTime.meanDays) + reviewPeriodDays` calendar days. `leadTime` is an object from the start, so that its
  standard deviation (roadmap R10: the lead time is an estimate that can be wrong) is not a breaking change later.
- **The calendar travels with the request.** Lead time and review period are calendar days, demand is per open day:
  to count the open days of the protection interval the service needs the calendar after `asOf`. One calendar per
  request, shared by every product, shaped like `BusinessCalendar` in the `demand` module: a range, both ends
  included, and its closed days.
- **History as explicit observations.** Every open day from the first observation to the last open day before
  `asOf`, zeros included, as date–quantity pairs. A compact form (one date list, one value array per product) would
  save bytes that do not matter at this scale (54 products, about 600 days) and cost readability.
- **Two levels of rejection.** A request that breaks a precondition is rejected as a whole with 422 and RFC 9457
  problem details listing every violation (at most 100) with a JSON Pointer: a calendar that does not cover history
  and horizon, a horizon shorter than a protection interval, a duplicate product, a gap in the history, an
  observation on a closed day or on or after `asOf`. These mean that the caller's query is wrong. A body that cannot
  be read as JSON gets 400. A product that is valid but cannot be forecast gets outcome `FAILED` and does not affect
  the others; an unexpected error in one product's computation becomes that product's `INTERNAL_ERROR`.
- **Flat per-product outcome** (option 3). `oneOf` with a discriminator is the more precise model, but discriminator
  support in openapi-generator's Java output has a long record of bugs, and the generated code is what the
  orchestrator lives with. The contract documents which fields `OK` guarantees.
- **Open strings where the set will grow.** `method`, `quantileMethod`, `SeriesError.code` and `Violation.code` are
  strings, not enums: adding a method or an error code must not break the client. `demandClass` and `granularity`
  are closed enums with every value defined up front (`WEEKLY` included, an open question of R1).
- **Reproducibility.** Every response carries the service version and the versions of the libraries that determine
  the numbers; the orchestrator stores them with the forecast version.
- **No nullable fields.** An absent field is absent, never `null`; the service serialises without `None` values.

### Toolchain

- **OpenAPI 3.0.3** (option 2). As of October 2026, support for 3.1 is beta in openapi-generator (enabled
  automatically, with no documented scope and no confirmed Java coverage) and in oasdiff (a separate beta build on a
  fork of its parser). The contract needs nothing that only 3.1 offers.
- **Behavioural conformance** (option 2), in `forecasting/tests/test_contract.py`:
  - the committed Pydantic models must equal a fresh generation from the contract;
  - the app's routes must equal the contract's operations;
  - Schemathesis generates requests from the contract, runs them in-process against the app and checks that every
    response has a documented status code and content type and is valid against its schema; a known-valid request
    checks the 200 response, which generated data almost never reaches.

  Option 1 would compare documents, not behaviour, and FastAPI's 3.1 document differs from a 3.0.3 contract in ways
  that do not matter (schema names, `nullable`, its own validation-error schemas), so it would need oasdiff's beta
  build to say little. FastAPI's own OpenAPI endpoint is switched off: the contract is the only document.
- **Schemathesis check excluded: "a schema-valid request is accepted".** The contract has preconditions that JSON
  Schema cannot express, so most generated requests are rightly rejected. Those preconditions are tested one by one.
- **Generated code.** Python models are generated by datamodel-code-generator, with its options in `pyproject.toml`,
  and committed so readers see them. The Java client will be generated at build time by openapi-generator's Maven
  plugin and not committed; that belongs to the pull request of the Spring `forecasting` module.
- **Python project.** uv, Python 3.13, FastAPI, Pydantic, uvicorn, pytest, ruff, the same layout as `seed/derive`.
  All dependencies are MIT or BSD-3-Clause, compatible with Apache 2.0.
- **Placeholder.** Until the methods exist, every product gets `FAILED` with `METHOD_NOT_IMPLEMENTED`: the service is
  runnable and conformant from the first commit, and `forecaster.py` is the seam where R1 plugs in.

### Left to the methods (R1)

- How lead time variability (`stdDays`) widens the quantile, and how demand is projected on days beyond the nominal
  end of the interval.
- The treatment of large wholesale orders: the contract carries raw quantities, and the method reports its choices in
  `parameters`.
- The codes of `FAILED` outcomes specific to the methods, for example a history too short to classify.

## Consequences

### Positive

- One boundary between estimating demand and deciding orders, easy to explain and to test on each side.
- A backtest cannot leak the future: the service rejects it.
- The contract can grow (methods, error codes, lead time variability) without breaking the Java client.
- CI tests what matters, the behaviour on the wire, and already found one gap: a body that is not valid UTF-8 got an
  undocumented 400 in FastAPI's own format.

### Negative

- The orchestrator must send the calendar and complete series with zeros; the `demand` module already produces them.
- A flat result type lets a client read a field that the outcome does not guarantee; the contract documents it.
- Contract preconditions live in hand-written code next to the generated models, and only tests keep them aligned
  with the descriptions in the contract.
- OpenAPI 3.0.3 will need a migration once the generators support 3.1 fully.
