from provisio_forecasting import forecaster
from provisio_forecasting.api.models import SeriesInput


def test_health(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_placeholder_fails_every_series_without_null_fields(client, payload):
    response = client.post("/v1/forecasts", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == "run-1"
    assert body["service"] == {"version": "0.1.0", "libraries": {}}
    # The contract has no nullable fields: absent means absent, never null.
    assert body["results"] == [
        {
            "sku": sku,
            "outcome": "FAILED",
            "error": {"code": "METHOD_NOT_IMPLEMENTED", "message": "No forecasting method is implemented yet"},
        }
        for sku in ("85099B", "20725")
    ]


def test_unexpected_error_affects_only_its_series(client, payload, monkeypatch):
    original = forecaster.forecast_series

    def failing_for_one(request, series: SeriesInput):
        if series.sku == "20725":
            raise RuntimeError("boom")
        return original(request, series)

    monkeypatch.setattr(forecaster, "forecast_series", failing_for_one)

    results = client.post("/v1/forecasts", json=payload).json()["results"]

    assert [(r["sku"], r["error"]["code"]) for r in results] == [
        ("85099B", "METHOD_NOT_IMPLEMENTED"),
        ("20725", "INTERNAL_ERROR"),
    ]


def test_schema_violation_is_a_problem_with_a_pointer(client, payload):
    del payload["series"][1]["serviceLevel"]
    payload["series"][0]["leadTime"]["meanDays"] = 0

    response = client.post("/v1/forecasts", json=payload)

    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    problem = response.json()
    assert problem["type"] == "about:blank"
    assert problem["status"] == 422
    assert {(e["pointer"], e["code"]) for e in problem["errors"]} == {
        ("/series/0/leadTime/meanDays", "SCHEMA_GREATER_THAN"),
        ("/series/1/serviceLevel", "SCHEMA_MISSING"),
    }


def test_malformed_json_is_a_bad_request(client):
    response = client.post("/v1/forecasts", content=b'{"requestId": ', headers={"content-type": "application/json"})

    assert response.status_code == 400
    assert response.headers["content-type"] == "application/problem+json"
    assert [(e["pointer"], e["code"]) for e in response.json()["errors"]] == [("", "SCHEMA_JSON_INVALID")]


def test_body_that_is_not_utf8_is_a_bad_request(client):
    response = client.post("/v1/forecasts", content=b"\xff\xfe{", headers={"content-type": "application/json"})

    assert response.status_code == 400
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["status"] == 400


def test_unknown_path_is_a_problem(client):
    response = client.get("/v2/forecasts")

    assert response.status_code == 404
    assert response.headers["content-type"] == "application/problem+json"


def test_precondition_violation_is_a_problem(client, payload):
    payload["series"][0]["observations"].append({"date": "2011-07-01", "quantity": 1})

    response = client.post("/v1/forecasts", json=payload)

    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    codes = [e["code"] for e in response.json()["errors"]]
    assert "OBSERVATION_NOT_BEFORE_AS_OF" in codes
