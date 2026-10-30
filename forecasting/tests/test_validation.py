from datetime import date

import pytest
from conftest import request_payload

from provisio_forecasting.api.models import ForecastRequest
from provisio_forecasting.protection import protection_interval_end
from provisio_forecasting.validation import MAX_VIOLATIONS, validate


def violations(payload: dict) -> set[tuple[str, str]]:
    return {(v.pointer, v.code) for v in validate(ForecastRequest.model_validate(payload))}


def index_of(observations: list[dict], day: str) -> int:
    return next(i for i, o in enumerate(observations) if o["date"] > day)


def test_valid_request_has_no_violations(payload):
    assert violations(payload) == set()


def horizon_on_as_of(p):
    p["horizonEnd"] = p["asOf"]


def empty_calendar(p):
    p["calendar"].update({"from": "2011-08-01", "closedDays": []})


def calendar_short_of_horizon(p):
    p["calendar"]["to"] = "2011-07-20"
    p["calendar"]["closedDays"] = [d for d in p["calendar"]["closedDays"] if d <= "2011-07-20"]


def duplicate_closed_day(p):
    p["calendar"]["closedDays"].append(p["calendar"]["closedDays"][0])


def closed_day_outside(p):
    p["calendar"]["closedDays"].append("2011-08-06")


def duplicate_sku(p):
    p["series"][1]["sku"] = "85099B"


def calendar_after_history(p):
    p["calendar"]["from"] = "2011-06-02"


def observation_on_saturday(p):
    observations = p["series"][0]["observations"]
    observations.insert(index_of(observations, "2011-06-11"), {"date": "2011-06-11", "quantity": 1})


def observations_swapped(p):
    observations = p["series"][0]["observations"]
    observations[3], observations[4] = observations[4], observations[3]


def observation_missing_inside(p):
    del p["series"][0]["observations"][5]


def last_observation_missing(p):
    p["series"][0]["observations"].pop()


def observation_on_as_of(p):
    p["series"][0]["observations"].append({"date": "2011-07-01", "quantity": 1})


def lead_time_beyond_horizon(p):
    p["series"][1]["leadTime"]["meanDays"] = 30


CLOSED_DAYS = len(request_payload()["calendar"]["closedDays"])


@pytest.mark.parametrize(
    ("mutate", "expected"),
    [
        (
            horizon_on_as_of,
            {
                ("/horizonEnd", "HORIZON_NOT_AFTER_AS_OF"),
                ("/series/0/leadTime", "PROTECTION_INTERVAL_BEYOND_HORIZON"),
                ("/series/1/leadTime", "PROTECTION_INTERVAL_BEYOND_HORIZON"),
            },
        ),
        # The series are not checked against a broken calendar: they would only repeat its problem.
        (empty_calendar, {("/calendar", "CALENDAR_EMPTY")}),
        (calendar_short_of_horizon, {("/calendar/to", "CALENDAR_DOES_NOT_COVER_HORIZON")}),
        (duplicate_closed_day, {(f"/calendar/closedDays/{CLOSED_DAYS}", "CLOSED_DAY_DUPLICATE")}),
        (closed_day_outside, {(f"/calendar/closedDays/{CLOSED_DAYS}", "CLOSED_DAY_OUTSIDE_CALENDAR")}),
        (duplicate_sku, {("/series/1/sku", "SKU_DUPLICATE")}),
        (
            calendar_after_history,
            {
                ("/series/0/observations/0/date", "CALENDAR_DOES_NOT_COVER_HISTORY"),
                ("/series/1/observations/0/date", "CALENDAR_DOES_NOT_COVER_HISTORY"),
            },
        ),
        (observation_on_saturday, {("/series/0/observations/9/date", "OBSERVATION_ON_CLOSED_DAY")}),
        (observations_swapped, {("/series/0/observations/4/date", "OBSERVATIONS_NOT_ASCENDING")}),
        (observation_missing_inside, {("/series/0/observations", "OBSERVATIONS_INCOMPLETE")}),
        (last_observation_missing, {("/series/0/observations", "OBSERVATIONS_INCOMPLETE")}),
        (observation_on_as_of, {("/series/0/observations/26/date", "OBSERVATION_NOT_BEFORE_AS_OF")}),
        (lead_time_beyond_horizon, {("/series/1/leadTime", "PROTECTION_INTERVAL_BEYOND_HORIZON")}),
    ],
    ids=lambda value: value.__name__ if callable(value) else "",
)
def test_precondition(payload, mutate, expected):
    mutate(payload)

    assert violations(payload) == expected


def test_violations_are_capped(payload):
    payload["calendar"]["closedDays"] += [payload["calendar"]["closedDays"][0]] * (MAX_VIOLATIONS + 50)

    assert len(validate(ForecastRequest.model_validate(payload))) == MAX_VIOLATIONS


def test_protection_interval_covers_rounded_up_lead_time_plus_review_period(payload):
    series = ForecastRequest.model_validate(payload).series[0]  # meanDays 10.5, reviewPeriodDays 7

    assert protection_interval_end(date(2011, 7, 1), series) == date(2011, 7, 18)  # 11 + 7 = 18 days
