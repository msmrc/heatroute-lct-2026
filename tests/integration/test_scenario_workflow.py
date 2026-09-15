import time
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from heatroute.api.app import create_app
from heatroute.db import SessionLocal
from heatroute.models import OutboxEvent
from heatroute.services import runs as run_services


def scenario_payload() -> dict[str, object]:
    return {
        "input_mode": "point_to_point_demo",
        "entry_point_wgs84": [37.61, 55.752],
        "goal_point_wgs84": [37.64, 55.752],
        "forbidden_rectangles_wgs84": [[37.623, 55.748, 37.628, 55.756]],
        "corridor_width_m": 8,
        "search_settings": {
            "resolution_m": 20,
            "search_buffer_m": 500,
            "budget": {"max_expanded_states": 100_000},
        },
        "explicit_assumptions": ["synthetic scenario API integration fixture"],
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
    pytest.fail("scenario run did not reach a terminal state")


@pytest.mark.integration
def test_project_scenario_revision_preflight_and_run_workflow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = TestClient(create_app())
    created = client.post(
        "/api/v1/projects",
        json={
            "name": "Scenario API integration project",
            "description": "synthetic integration fixture",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    )
    assert created.status_code == 201
    project = created.json()
    project_id = project["id"]

    missing_if_match = client.patch(
        f"/api/v1/projects/{project_id}", json={"name": "updated"}
    )
    assert missing_if_match.status_code == 428
    updated = client.patch(
        f"/api/v1/projects/{project_id}",
        json={"name": "Scenario API integration project v2"},
        headers={"If-Match": '"1"'},
    )
    assert updated.status_code == 200
    assert updated.json()["current_revision"] == 2
    stale = client.patch(
        f"/api/v1/projects/{project_id}",
        json={"description": "stale write"},
        headers={"If-Match": "1"},
    )
    assert stale.status_code == 412

    scenario = client.post(
        f"/api/v1/projects/{project_id}/scenarios",
        json={"name": "Obstacle bypass"},
    )
    assert scenario.status_code == 201
    scenario_id = scenario.json()["id"]

    missing_precondition = client.post(
        f"/api/v1/scenarios/{scenario_id}/revisions",
        json=scenario_payload(),
    )
    assert missing_precondition.status_code == 428
    revision_response = client.post(
        f"/api/v1/scenarios/{scenario_id}/revisions",
        json=scenario_payload(),
        headers={"If-Match": "0"},
    )
    assert revision_response.status_code == 201
    revision = revision_response.json()
    assert revision["revision"] == 1

    stale_revision = client.post(
        f"/api/v1/scenarios/{scenario_id}/revisions",
        json=scenario_payload(),
        headers={"If-Match": "0"},
    )
    assert stale_revision.status_code == 412

    preflight = client.post(f"/api/v1/scenario-revisions/{revision['id']}/preflight")
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is True
    assert {finding["code"] for finding in preflight.json()["findings"]} == {
        "ENGINEERING_CHECKS_NOT_PERFORMED"
    }

    idempotency_key = str(uuid4())
    first = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": idempotency_key},
    )
    assert first.status_code == 202
    replay = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": idempotency_key},
    )
    assert replay.status_code == 202
    assert replay.json()["run_id"] == first.json()["run_id"]
    run = wait_for_run(client, first.json()["run_id"])
    assert run["job_state"] == "succeeded"
    assert run["outcome"] == "routes_found"
    assert run["alternatives"]
    events = client.get(f"/api/v1/runs/{first.json()['run_id']}/events")
    assert events.status_code == 200
    event_rows = events.json()
    assert [event["event_type"] for event in event_rows][0] == "run.queued"
    assert [event["event_type"] for event in event_rows][-1] == "run.completed"
    assert [event["sequence"] for event in event_rows] == sorted(
        event["sequence"] for event in event_rows
    )
    tail = client.get(
        f"/api/v1/runs/{first.json()['run_id']}/events",
        params={"after_sequence": event_rows[-2]["sequence"]},
    )
    assert [event["event_type"] for event in tail.json()] == ["run.completed"]

    wider_payload = scenario_payload()
    wider_payload["corridor_width_m"] = 12
    wider_revision_response = client.post(
        f"/api/v1/scenarios/{scenario_id}/revisions",
        json=wider_payload,
        headers={"If-Match": "1"},
    )
    assert wider_revision_response.status_code == 201
    wider_revision = wider_revision_response.json()
    assert wider_revision["revision"] == 2
    assert wider_revision["input_hash"] != revision["input_hash"]
    wider_accepted = client.post(
        f"/api/v1/scenario-revisions/{wider_revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert wider_accepted.status_code == 202
    wider_run = wait_for_run(client, wider_accepted.json()["run_id"])
    assert wider_run["job_state"] == "succeeded"
    assert wider_run["id"] != run["id"]
    assert wider_run["versions_snapshot"]["scenario_input_hash"] == wider_revision[
        "input_hash"
    ]

    cancellable = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={"algorithm": "dijkstra"},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert cancellable.status_code == 202
    cancel = client.post(f"/api/v1/runs/{cancellable.json()['run_id']}/cancel")
    assert cancel.status_code == 202
    cancelled_run = wait_for_run(client, cancellable.json()["run_id"])
    assert cancelled_run["job_state"] == "cancelled"
    assert cancelled_run["outcome"] == "cancelled"

    def broker_unavailable(*_args: object, **_kwargs: object) -> None:
        raise ConnectionError("synthetic broker outage")

    monkeypatch.setattr(run_services.celery_app, "send_task", broker_unavailable)
    recoverable = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert recoverable.status_code == 202
    recovered_run = wait_for_run(client, recoverable.json()["run_id"])
    assert recovered_run["job_state"] == "succeeded"
    with SessionLocal() as session:
        recovered_event = session.scalar(
            select(OutboxEvent).where(
                OutboxEvent.aggregate_id == UUID(recoverable.json()["run_id"]),
                OutboxEvent.event_type == "run.requested",
            )
        )
        assert recovered_event is not None
        assert recovered_event.state == "published"
        assert recovered_event.attempts >= 2


@pytest.mark.integration
def test_preflight_blocks_unconfirmed_working_crs() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": "Unconfirmed CRS integration project",
            "working_crs": "EPSG:32637",
            "crs_confirmed": False,
            "source_mode": "synthetic",
        },
    ).json()
    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios",
        json={"name": "Blocked scenario"},
    ).json()
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        json=scenario_payload(),
        headers={"If-Match": "0"},
    ).json()

    preflight = client.post(f"/api/v1/scenario-revisions/{revision['id']}/preflight")
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is False
    assert "WORKING_CRS_UNCONFIRMED" in {
        finding["code"] for finding in preflight.json()["findings"]
    }
    blocked = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert blocked.status_code == 409
    assert blocked.json()["detail"]["code"] == "PREFLIGHT_FAILED"
