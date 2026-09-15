import time

import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app


@pytest.mark.integration
def test_hydraulic_calculation_runs_in_worker_and_persists_result() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/engineering/hydraulics/calculate",
        json={
            "nodes": [
                {
                    "node_id": "source",
                    "nominal_pressure_bar": 6,
                    "temperature_k": 353.15,
                    "elevation_m": 150,
                },
                {
                    "node_id": "consumer",
                    "nominal_pressure_bar": 6,
                    "temperature_k": 353.15,
                    "elevation_m": 152,
                },
            ],
            "pipes": [
                {
                    "pipe_id": "P-1",
                    "from_node_id": "source",
                    "to_node_id": "consumer",
                    "length_m": 200,
                    "inner_diameter_mm": 100,
                    "roughness_mm": 0.1,
                    "loss_coefficient": 1,
                }
            ],
            "boundaries": [
                {"node_id": "source", "pressure_bar": 6, "temperature_k": 353.15}
            ],
            "demands": [{"node_id": "consumer", "mass_flow_kg_per_s": 2}],
            "thresholds": {
                "minimum_pressure_bar": 2,
                "maximum_velocity_m_per_s": 2,
                "mass_balance_tolerance_kg_per_s": 0.0001,
            },
        },
    )

    assert response.status_code == 202
    accepted = response.json()
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result_response = client.get(accepted["status_url"])
        assert result_response.status_code == 200
        body = result_response.json()
        if body["state"] in {"succeeded", "failed"}:
            break
        time.sleep(0.1)
    else:
        pytest.fail("hydraulic job did not reach a terminal state")

    assert body["state"] == "succeeded", body
    assert body["result"]["status"] == "passed"
    assert body["result"]["solver"] == {
        "name": "pandapipes",
        "version": "0.14.0",
        "mode": "hydraulics",
    }
    assert body["result"]["balances"]["mass_balance_error_kg_per_s"] == 0
