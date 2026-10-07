# Forecasting service

Stateless Python service (FastAPI) computing demand forecasts, safety stock and reorder points
with classical statistical methods. Unaware of Camunda and without database access
([ADR 0005](../docs/adr/0005-python-stateless-forecasting-service.md),
[ADR 0006](../docs/adr/0006-precomputed-forecast-with-on-demand-recompute.md)).

The OpenAPI specification is the source of truth; models are generated from it.
