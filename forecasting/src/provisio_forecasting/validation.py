"""Preconditions of the contract that JSON Schema cannot express (ADR 0028).

A violation rejects the whole request with 422: it means the caller's query is wrong, not that one product is hard to
forecast. Every check reports a JSON Pointer to the offending part, using the field names of the contract.
"""

from datetime import date

from provisio_forecasting.api.models import ForecastRequest, SeriesInput, Violation
from provisio_forecasting.calendar import OpenDays
from provisio_forecasting.protection import protection_interval_end

MAX_VIOLATIONS = 100


def pointer(*parts: str | int) -> str:
    """JSON Pointer (RFC 6901) built from path segments."""
    return "".join("/" + str(part).replace("~", "~0").replace("/", "~1") for part in parts)


def validate(request: ForecastRequest) -> list[Violation]:
    """Every precondition violation found, at most `MAX_VIOLATIONS`."""
    violations = _check_request(request)
    if not any(v.pointer.startswith("/calendar") for v in violations):
        # Series checks rely on a sound calendar; without one they would only repeat the calendar's problem.
        open_days = OpenDays(request.calendar)
        for index, series in enumerate(request.series):
            violations.extend(_check_series(request, index, series, open_days))
    return violations[:MAX_VIOLATIONS]


def _violation(at: str, code: str, message: str) -> Violation:
    return Violation(pointer=at, code=code, message=message)


def _check_request(request: ForecastRequest) -> list[Violation]:
    violations = []
    calendar = request.calendar
    if request.horizon_end <= request.as_of:
        violations.append(
            _violation(
                pointer("horizonEnd"),
                "HORIZON_NOT_AFTER_AS_OF",
                f"horizonEnd {request.horizon_end} is not after asOf {request.as_of}",
            )
        )
    if calendar.from_ > calendar.to:
        violations.append(
            _violation(
                pointer("calendar"), "CALENDAR_EMPTY", f"calendar from {calendar.from_} is after to {calendar.to}"
            )
        )
    if calendar.to < request.horizon_end:
        violations.append(
            _violation(
                pointer("calendar", "to"),
                "CALENDAR_DOES_NOT_COVER_HORIZON",
                f"calendar ends on {calendar.to}, before horizonEnd {request.horizon_end}",
            )
        )
    seen_closed: set[date] = set()
    for index, day in enumerate(calendar.closed_days):
        at = pointer("calendar", "closedDays", index)
        if day in seen_closed:
            violations.append(_violation(at, "CLOSED_DAY_DUPLICATE", f"closed day {day} is listed more than once"))
        elif not calendar.from_ <= day <= calendar.to:
            violations.append(
                _violation(
                    at,
                    "CLOSED_DAY_OUTSIDE_CALENDAR",
                    f"closed day {day} is outside the calendar {calendar.from_}..{calendar.to}",
                )
            )
        seen_closed.add(day)
    seen_skus: set[str] = set()
    for index, series in enumerate(request.series):
        if series.sku in seen_skus:
            violations.append(
                _violation(pointer("series", index, "sku"), "SKU_DUPLICATE", f"sku {series.sku} appears more than once")
            )
        seen_skus.add(series.sku)
    return violations


def _check_series(request: ForecastRequest, index: int, series: SeriesInput, open_days: OpenDays) -> list[Violation]:
    violations = []
    base = ("series", index)
    observations = series.observations

    first = observations[0].date
    if first < open_days.first:
        violations.append(
            _violation(
                pointer(*base, "observations", 0, "date"),
                "CALENDAR_DOES_NOT_COVER_HISTORY",
                f"history starts on {first}, before the calendar starts on {open_days.first}",
            )
        )

    previous: date | None = None
    order_and_days_ok = True
    for position, observation in enumerate(observations):
        at = pointer(*base, "observations", position, "date")
        day = observation.date
        if day >= request.as_of:
            violations.append(
                _violation(
                    at, "OBSERVATION_NOT_BEFORE_AS_OF", f"observation on {day} is not before asOf {request.as_of}"
                )
            )
            order_and_days_ok = False
        elif open_days.covers(day) and not open_days.is_open(day):
            violations.append(_violation(at, "OBSERVATION_ON_CLOSED_DAY", f"observation on {day}, a closed day"))
            order_and_days_ok = False
        if previous is not None and day <= previous:
            violations.append(
                _violation(at, "OBSERVATIONS_NOT_ASCENDING", f"observation on {day} does not follow {previous}")
            )
            order_and_days_ok = False
        previous = day

    if order_and_days_ok and first >= open_days.first:
        # Dates are ascending open days before asOf: the series is complete exactly when it matches the calendar.
        expected = list(open_days.between(first, request.as_of))
        actual = [observation.date for observation in observations]
        if actual != expected:
            present = set(actual)
            missing = next((day for day in expected if day not in present), None)
            violations.append(
                _violation(
                    pointer(*base, "observations"),
                    "OBSERVATIONS_INCOMPLETE",
                    f"observations must cover every open day from {first} to the last open day before {request.as_of}; "
                    f"first missing day: {missing}",
                )
            )

    end = protection_interval_end(request.as_of, series)
    if end > request.horizon_end:
        violations.append(
            _violation(
                pointer(*base, "leadTime"),
                "PROTECTION_INTERVAL_BEYOND_HORIZON",
                f"the protection interval ends on {end}, after horizonEnd {request.horizon_end}",
            )
        )
    return violations
