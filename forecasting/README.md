# Forecasting service

Stateless Python service (FastAPI) forecasting daily demand with classical statistical methods. Unaware of Camunda
and without database access ([ADR 0005](../docs/adr/0005-python-stateless-forecasting-service.md),
[ADR 0006](../docs/adr/0006-precomputed-forecast-with-on-demand-recompute.md)).

It returns, per product, a forecast and the demand over the protection interval (lead time plus review period) at
the requested service level. It does not compute order quantities: stock on hand and on order, minimum order
quantities and supplier terms belong to the orchestrator ([ADR 0028](../docs/adr/0028-forecasting-contract-and-toolchain.md)).

No forecasting method is implemented yet: every product gets outcome `FAILED` with code `METHOD_NOT_IMPLEMENTED`.
The methods go in [`forecaster.py`](src/provisio_forecasting/forecaster.py) (roadmap R1).

## Contract

[`contracts/forecasting/openapi.yaml`](../contracts/forecasting/openapi.yaml) is the source of truth.
The Pydantic models in [`api/models.py`](src/provisio_forecasting/api/models.py) are generated from it and committed;
never edit them by hand. After changing the contract:

```bash
uv run datamodel-codegen   # options in [tool.datamodel-codegen], pyproject.toml
```

The tests fail when the models are stale, when the routes differ from the contract's operations, or when the app
answers anything the contract does not document (Schemathesis).

| Module | Role |
| --- | --- |
| `app.py` | HTTP layer: routes, problem details, one product's failure isolated from the others |
| `validation.py` | Preconditions the JSON Schema cannot express (complete history, open days, nothing on or after `asOf`) |
| `calendar.py`, `protection.py` | Open days and the protection interval, as the contract defines them |
| `forecaster.py` | Forecasting methods (placeholder) |

## Development

Requires [uv](https://docs.astral.sh/uv/).

```bash
uv run pytest                                    # tests, contract conformance included
uv run ruff check . && uv run ruff format --check .
uv run uvicorn provisio_forecasting.app:app --reload   # http://localhost:8000/health
```
