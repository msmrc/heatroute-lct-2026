import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from shapely.geometry import box, shape

from heatroute.api.app import create_app


def demo_payload(*, obstacle: bool) -> dict[str, object]:
    return {
        "input_mode": "point_to_point_demo",
        "entry_point_wgs84": [37.61, 55.752],
        "goal_point_wgs84": [37.64, 55.752],
        "forbidden_rectangles_wgs84": (
            [[37.623, 55.748, 37.628, 55.756]] if obstacle else []
        ),
        "corridor_width_m": 8,
        "algorithm": "astar",
        "search_settings": {
            "resolution_m": 20,
            "search_buffer_m": 500,
            "budget": {"max_expanded_states": 100_000},
        },
        "explicit_assumptions": ["synthetic integration fixture"],
    }


def wait_for_run(client: TestClient, run_id: str) -> dict[str, object]:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/runs/{run_id}")
        assert response.status_code == 200
        run = response.json()
        if run["job_state"] in {"succeeded", "partial", "failed", "cancelled"}:
            return run
        time.sleep(0.1)
    pytest.fail("demo run did not reach a terminal state")


@pytest.mark.integration
def test_demo_run_is_persisted_idempotent_and_changes_with_obstacle() -> None:
    client = TestClient(create_app())
    key = str(uuid4())
    payload = demo_payload(obstacle=True)
    first = client.post("/api/v1/demo/runs", json=payload, headers={"Idempotency-Key": key})
    assert first.status_code == 202
    replay = client.post("/api/v1/demo/runs", json=payload, headers={"Idempotency-Key": key})
    assert replay.status_code == 202
    assert replay.json()["run_id"] == first.json()["run_id"]
    changed = client.post(
        "/api/v1/demo/runs",
        json=demo_payload(obstacle=False),
        headers={"Idempotency-Key": key},
    )
    assert changed.status_code == 409

    obstacle_run = wait_for_run(client, first.json()["run_id"])
    assert obstacle_run["outcome"] == "routes_found"
    obstacle_alternative = obstacle_run["alternatives"][0]
    obstacle = box(37.623, 55.748, 37.628, 55.756)
    assert not shape(obstacle_alternative["corridor_wgs84"]).intersects(obstacle)

    direct = client.post(
        "/api/v1/demo/runs",
        json=demo_payload(obstacle=False),
        headers={"Idempotency-Key": str(uuid4())},
    )
    direct_run = wait_for_run(client, direct.json()["run_id"])
    direct_alternative = direct_run["alternatives"][0]
    assert direct_alternative["geometry_hash"] != obstacle_alternative["geometry_hash"]
    assert (
        direct_alternative["metrics"]["route_length_m"]
        < obstacle_alternative["metrics"]["route_length_m"]
    )
