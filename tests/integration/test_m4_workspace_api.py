import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app


@pytest.mark.integration
def test_m4_workspace_lists_history_jobs_and_server_exports() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"M4 workspace {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios",
        json={"name": "UI route"},
    ).json()
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        headers={"If-Match": "0"},
        json={
            "input_mode": "point_to_point_demo",
            "entry_point_wgs84": [37.61, 55.752],
            "goal_point_wgs84": [37.625, 55.752],
            "forbidden_rectangles_wgs84": [[37.616, 55.750, 37.619, 55.753]],
            "corridor_width_m": 6,
            "construction_methods": ["open_trench"],
            "objective_profiles": ["shortest"],
            "validation_mode": "exploratory",
            "explicit_assumptions": ["SYNTHETIC_UI_TEST"],
            "search_settings": {
                "resolution_m": 10,
                "search_buffer_m": 250,
                "max_alternatives": 2,
                "budget": {"max_expanded_states": 100_000},
            },
        },
    ).json()
    accepted = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={"algorithm": "astar"},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert accepted.status_code == 202, accepted.text
    run_id = accepted.json()["run_id"]
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        snapshot = client.get(f"/api/v1/runs/{run_id}").json()
        if snapshot["job_state"] in {"succeeded", "partial", "failed", "cancelled"}:
            break
        time.sleep(0.1)
    else:
        pytest.fail("route run did not reach a terminal state")
    assert snapshot["job_state"] == "succeeded"

    scenarios = client.get(f"/api/v1/projects/{project['id']}/scenarios")
    assert scenarios.status_code == 200
    assert scenarios.json()[0]["revisions"][0]["id"] == revision["id"]
    assert client.get(f"/api/v1/projects/{project['id']}/imports").json() == []

    history = client.get(f"/api/v1/projects/{project['id']}/runs")
    assert history.status_code == 200
    assert history.json()[0]["id"] == run_id
    assert history.json()[0]["alternatives_count"] >= 1

    jobs = client.get("/api/v1/jobs", params={"project_id": project["id"]})
    assert jobs.status_code == 200
    route_job = next(item for item in jobs.json() if item["resource_id"] == run_id)
    assert route_job["resource_type"] == "run"
    assert route_job["project_id"] == project["id"]

    expected_media = {
        "geojson": "application/geo+json",
        "json": "application/json",
        "csv": "text/csv",
        "html": "text/html",
    }
    for format, media_type in expected_media.items():
        exported = client.get(f"/api/v1/runs/{run_id}/export", params={"format": format})
        assert exported.status_code == 200
        assert exported.headers["content-type"].startswith(media_type)
        assert f"heatroute-run-{run_id}" in exported.headers["content-disposition"]

    capabilities = client.get("/api/v1/capabilities").json()
    assert capabilities["exports"] == ["geojson", "json", "csv", "html"]


@pytest.mark.integration
def test_mutations_create_scoped_audit_events() -> None:
    client = TestClient(create_app())
    request_id = f"audit-{uuid4()}"
    created = client.post(
        "/api/v1/projects",
        json={"name": "Audited project", "source_mode": "synthetic"},
        headers={"X-Request-ID": request_id},
    )
    assert created.status_code == 201, created.text

    audit = client.get("/api/v1/audit-events", params={"limit": 20})
    assert audit.status_code == 200, audit.text
    event = next(item for item in audit.json() if item["request_id"] == request_id)
    assert event["method"] == "POST"
    assert event["path"] == "/api/v1/projects"
    assert event["status_code"] == 201
    assert event["action"] == "mutation"
    assert "name" not in event["metadata"]
