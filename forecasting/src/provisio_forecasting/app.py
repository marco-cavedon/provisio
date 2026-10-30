"""HTTP layer of the forecasting service: binds the contract to the code (ADR 0005, 0028).

The contract is `contracts/forecasting/openapi.yaml`, not the document FastAPI would generate, so FastAPI's own
OpenAPI endpoint and docs are switched off.
"""

import logging
from http import HTTPStatus
from importlib.metadata import version

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from provisio_forecasting import forecaster
from provisio_forecasting.api.models import (
    ForecastRequest,
    ForecastResponse,
    Health,
    Outcome,
    Problem,
    SeriesError,
    SeriesInput,
    SeriesResult,
    ServiceInfo,
    Status,
    Violation,
)
from provisio_forecasting.validation import MAX_VIOLATIONS, pointer, validate

logger = logging.getLogger(__name__)

PROBLEM_MEDIA_TYPE = "application/problem+json"


class ContractViolation(Exception):
    def __init__(self, violations: list[Violation]) -> None:
        super().__init__(f"{len(violations)} contract violation(s)")
        self.violations = violations


def _problem(
    status: int, title: str, violations: list[Violation] | None = None, detail: str | None = None
) -> JSONResponse:
    problem = Problem(
        type="about:blank",
        title=title,
        status=status,
        detail=detail,
        errors=violations[:MAX_VIOLATIONS] if violations else None,
    )
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(mode="json", by_alias=True, exclude_none=True),
        media_type=PROBLEM_MEDIA_TYPE,
    )


def _contract_problem(violations: list[Violation]) -> JSONResponse:
    return _problem(422, "The request breaks the forecasting contract", violations)


UNREADABLE_BODY = "The request body cannot be read as JSON"


def _schema_violation(error: dict) -> Violation:
    location = list(error.get("loc", ()))
    if location and location[0] == "body":
        location = location[1:]
    if error.get("type") == "json_invalid":
        location = []  # the location is a character offset, not a path
    return Violation(
        pointer=pointer(*location),
        code="SCHEMA_" + str(error.get("type", "invalid")).upper(),
        message=str(error.get("msg", "invalid value")),
    )


def create_app() -> FastAPI:
    app = FastAPI(
        title="Provisio forecasting service",
        version=version("provisio-forecasting"),
        openapi_url=None,
        docs_url=None,
        redoc_url=None,
    )

    # Every error leaves as problem details. 400: the body cannot be read; 422: it can, but breaks the contract.
    @app.exception_handler(RequestValidationError)
    async def on_schema_violation(_: Request, exc: RequestValidationError) -> JSONResponse:
        violations = [_schema_violation(error) for error in exc.errors()]
        if any(violation.code == "SCHEMA_JSON_INVALID" for violation in violations):
            return _problem(400, UNREADABLE_BODY, violations)
        return _contract_problem(violations)

    @app.exception_handler(ContractViolation)
    async def on_contract_violation(_: Request, exc: ContractViolation) -> JSONResponse:
        return _contract_problem(exc.violations)

    @app.exception_handler(HTTPException)
    async def on_http_error(_: Request, exc: HTTPException) -> JSONResponse:
        # Raised by the framework itself, for example a body that is not valid UTF-8 (400) or an unknown path (404).
        title = UNREADABLE_BODY if exc.status_code == 400 else HTTPStatus(exc.status_code).phrase
        response = _problem(exc.status_code, title, detail=str(exc.detail))
        response.headers.update(exc.headers or {})
        return response

    @app.get("/health", response_model=Health)
    def health() -> Health:
        return Health(status=Status.up)

    @app.post("/v1/forecasts", response_model=ForecastResponse, response_model_exclude_none=True)
    def compute_forecasts(request: ForecastRequest) -> ForecastResponse:
        violations = validate(request)
        if violations:
            raise ContractViolation(violations)
        return ForecastResponse(
            request_id=request.request_id,
            service=ServiceInfo(version=app.version, libraries=forecaster.LIBRARIES),
            results=[_forecast_isolated(request, series) for series in request.series],
        )

    return app


def _forecast_isolated(request: ForecastRequest, series: SeriesInput) -> SeriesResult:
    """One product's failure must not affect the others: an unexpected error becomes that product's outcome."""
    try:
        return forecaster.forecast_series(request, series)
    except Exception:
        logger.exception("Forecast failed for sku %s (request %s)", series.sku, request.request_id)
        return SeriesResult(
            sku=series.sku,
            outcome=Outcome.failed,
            error=SeriesError(code="INTERNAL_ERROR", message="Unexpected error while forecasting this product"),
        )


app = create_app()
