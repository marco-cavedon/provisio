"""The service conforms to contracts/forecasting/openapi.yaml (ADR 0028).

From the cheapest check: the committed models match a fresh generation, the routes match the contract's operations,
and the running app behaves as the contract says, on requests generated from it and on a known-valid request.
"""

import subprocess
import sys
import tempfile
from pathlib import Path

import schemathesis
import yaml
from conftest import CONTRACT, request_payload
from fastapi.routing import APIRoute
from schemathesis.specs.openapi.checks import positive_data_acceptance

from provisio_forecasting.app import create_app

PROJECT = Path(__file__).resolve().parents[1]
MODELS = PROJECT / "src" / "provisio_forecasting" / "api" / "models.py"


def test_committed_models_match_the_contract():
    # The options come from [tool.datamodel-codegen] in pyproject.toml; only the output is redirected. It stays inside
    # the project because the generator formats with ruff, which reads its settings from the nearest pyproject.toml.
    with tempfile.TemporaryDirectory(dir=PROJECT) as directory:
        generated = Path(directory) / "models.py"
        subprocess.run(
            [Path(sys.executable).parent / "datamodel-codegen", "--output", str(generated)],
            cwd=PROJECT,
            check=True,
            capture_output=True,
        )

        assert generated.read_text() == MODELS.read_text(), "models are stale: run `uv run datamodel-codegen`"


def test_routes_match_the_contract_operations():
    contract = yaml.safe_load(CONTRACT.read_text())
    documented = {
        (path, method.upper())
        for path, operations in contract["paths"].items()
        for method in operations
        if method in {"get", "put", "post", "delete", "patch"}
    }
    served = {
        (route.path, method) for route in create_app().routes if isinstance(route, APIRoute) for method in route.methods
    }

    assert served == documented


schema = schemathesis.openapi.from_path(CONTRACT)
schema.app = create_app()


@schema.parametrize()
def test_app_behaves_as_the_contract_says(case):
    # Checks: no server errors, documented status codes and content types, responses valid against the schemas.
    # One check is excluded: "a schema-valid request is accepted". The contract has preconditions that JSON Schema
    # cannot express (a complete history, open days only, nothing on or after asOf), so most generated requests are
    # rightly rejected with 422. Those preconditions are tested one by one in test_validation.py.
    case.call_and_validate(excluded_checks=[positive_data_acceptance])


def test_a_valid_request_gets_a_response_valid_against_the_contract():
    # Generated requests almost never satisfy the preconditions, so the 200 response is checked here explicitly.
    case = schema["/v1/forecasts"]["POST"].Case(body=request_payload(), media_type="application/json")

    response = case.call_and_validate()

    assert response.status_code == 200
