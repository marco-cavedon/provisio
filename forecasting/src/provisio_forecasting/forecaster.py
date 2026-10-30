"""Where the forecasting methods go (roadmap R1, owner-authored).

This placeholder keeps the service runnable and conformant to the contract before any method exists: every product
gets outcome `FAILED` with code `METHOD_NOT_IMPLEMENTED`. The request reaching this module has already passed the
contract's preconditions (`validation.py`), so a method can rely on them.
"""

from provisio_forecasting.api.models import ForecastRequest, Outcome, SeriesError, SeriesInput, SeriesResult

# Versions of the libraries that determine the numbers, reported in every response. Empty until a method uses one.
LIBRARIES: dict[str, str] = {}


def forecast_series(request: ForecastRequest, series: SeriesInput) -> SeriesResult:
    return SeriesResult(
        sku=series.sku,
        outcome=Outcome.failed,
        error=SeriesError(code="METHOD_NOT_IMPLEMENTED", message="No forecasting method is implemented yet"),
    )
