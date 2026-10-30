from datetime import date, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from provisio_forecasting.app import create_app

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "forecasting" / "openapi.yaml"

# A small calendar: June and July 2011, closed on Saturdays and on 4 June.
CALENDAR_FROM = date(2011, 6, 1)
CALENDAR_TO = date(2011, 7, 31)
AS_OF = date(2011, 7, 1)
HORIZON_END = date(2011, 7, 31)


def closed_days() -> list[date]:
    days = [CALENDAR_FROM + timedelta(days=n) for n in range((CALENDAR_TO - CALENDAR_FROM).days + 1)]
    return sorted({day for day in days if day.weekday() == 5} | {date(2011, 6, 4)})


def open_days_before_as_of() -> list[date]:
    closed = set(closed_days())
    days = [CALENDAR_FROM + timedelta(days=n) for n in range((AS_OF - CALENDAR_FROM).days)]
    return [day for day in days if day not in closed]


def series(sku: str) -> dict:
    return {
        "sku": sku,
        "observations": [
            {"date": day.isoformat(), "quantity": float(n % 3 == 0) * 4}
            for n, day in enumerate(open_days_before_as_of())
        ],
        "leadTime": {"meanDays": 10.5, "stdDays": 2},
        "reviewPeriodDays": 7,
        "serviceLevel": 0.95,
    }


def request_payload() -> dict:
    return {
        "requestId": "run-1",
        "asOf": AS_OF.isoformat(),
        "horizonEnd": HORIZON_END.isoformat(),
        "calendar": {
            "from": CALENDAR_FROM.isoformat(),
            "to": CALENDAR_TO.isoformat(),
            "closedDays": [day.isoformat() for day in closed_days()],
        },
        "series": [series("85099B"), series("20725")],
    }


@pytest.fixture
def payload() -> dict:
    return request_payload()


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app())
