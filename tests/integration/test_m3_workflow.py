import time
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app
from heatroute.db import SessionLocal
from heatroute.models import CalculationRun, Job, OutboxEvent
from heatroute.services import runs as run_services
from heatroute.workers.tasks import _compute_route, _recover_stale_route_jobs


def _catalog(version: int = 1, pipe_rate: str = "5") -> dict[str, object]:
    return {
        "id": "heatroute-demo-synthetic-costs",
        "version": version,
        "currency": "RUB",
        "price_date": "2026-09-08",
        "estimate_status": "synthetic",
        "region_scope": "test fixture",
        "tax_policy": "excluded",
        "rounding_policy": "ROUND_HALF_UP_2_DECIMALS",
        "exclusions": ["Not a commercial estimate"],
        "items": [
            {
                "code": "TRENCH",
                "description": "Synthetic trench",
                "quantity_unit": "m",
                "per": "corridor_m",
                "applies_to_method": "open_trench",
                "rate": "10",
                "source_reference": "synthetic fixture",
            },
            {
                "code": "PIPE",
                "description": "Synthetic pipe",
                "quantity_unit": "m",
                "per": "pipe_m",
                "applies_to_method": "all",
                "rate": pipe_rate,
                "source_reference": "synthetic fixture",
            },
            {
                "code": "RESTORE",
                "description": "Synthetic restoration",
                "quantity_unit": "m2",
                "per": "m2",
                "applies_to_method": "open_trench",
                "rate": "1",
                "source_reference": "synthetic fixture",
            },
            {
                "code": "TIE",
                "description": "Synthetic tie-in",
                "quantity_unit": "event",
                "per": "event",
                "applies_to_method": "demo_tie_in",
                "rate": "100",
                "source_reference": "synthetic fixture",
            },
            {
                "code": "BEND",
                "description": "Synthetic bend",
                "quantity_unit": "item",
                "per": "item",
                "applies_to_method": "all",
                "rate": "1",
                "source_reference": "synthetic fixture",
            },
        ],
    }


def _wait(client: TestClient, run_id: str) -> dict[str, object]:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        response = client.get(f"/api/v1/runs/{run_id}")
        assert response.status_code == 200
        result = response.json()
        if result["job_state"] in {"succeeded", "partial", "failed", "cancelled"}:
            return result
        time.sleep(0.1)
    pytest.fail("M3 run did not finish")


@pytest.mark.integration
def test_cost_exports_neutralize_csv_formulas_and_escape_html() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={"name": f"Export security {uuid4()}", "source_mode": "synthetic"},
    ).json()
    definition = _catalog()
    definition["items"][0]["description"] = "=HYPERLINK(\"https://invalid\")"
    definition["items"][1]["description"] = "<script>alert(1)</script>"
    created = client.post(
        f"/api/v1/projects/{project['id']}/cost-catalogs",
        json={"name": "=Unsafe catalog label", "definition": definition},
    )
    assert created.status_code == 201, created.text
    catalog_id = created.json()["id"]

    csv_export = client.get(
        f"/api/v1/cost-catalogs/{catalog_id}/export", params={"format": "csv"}
    )
    assert csv_export.status_code == 200
    assert "'=Unsafe catalog label" in csv_export.text
    assert "'=HYPERLINK" in csv_export.text

    html_export = client.get(
        f"/api/v1/cost-catalogs/{catalog_id}/export", params={"format": "html"}
    )
    assert html_export.status_code == 200
    assert "<script>" not in html_export.text
    assert "&lt;script&gt;alert(1)&lt;/script&gt;" in html_export.text


@pytest.mark.integration
def test_m3_catalog_objectives_cache_sse_and_duplicate_delivery() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"M3 project {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    catalog_response = client.post(
        f"/api/v1/projects/{project['id']}/cost-catalogs",
        json={"name": "Synthetic demo prices", "definition": _catalog()},
    )
    assert catalog_response.status_code == 201
    catalog = catalog_response.json()
    version_id = catalog["versions"][0]["id"]
    for format, marker in (("json", "synthetic"), ("csv", "catalog_label"), ("html", "<table>")):
        exported = client.get(
            f"/api/v1/cost-catalogs/{catalog['id']}/export", params={"format": format}
        )
        assert exported.status_code == 200
        assert marker in exported.text

    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios", json={"name": "M3 route"}
    ).json()
    payload = {
        "input_mode": "point_to_point_demo",
        "entry_point_wgs84": [37.61, 55.752],
        "goal_point_wgs84": [37.625, 55.752],
        "waypoints_wgs84": [[37.6175, 55.754]],
        "corridor_width_m": 4,
        "circuit_layout": "paired",
        "construction_methods": ["open_trench"],
        "objective_profiles": ["shortest", "estimated_cost"],
        "cost_catalog_version_id": version_id,
        "explicit_assumptions": ["SYNTHETIC_ONLY"],
        "search_settings": {
            "resolution_m": 10,
            "search_buffer_m": 200,
            "max_alternatives": 2,
            "budget": {"max_expanded_states": 100000},
        },
    }
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        json=payload,
        headers={"If-Match": "0"},
    ).json()
    preflight = client.post(f"/api/v1/scenario-revisions/{revision['id']}/preflight")
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is True
    assert "WAYPOINTS_NOT_SUPPORTED" not in {
        finding["code"] for finding in preflight.json()["findings"]
    }

    accepted = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert accepted.status_code == 202
    run = _wait(client, accepted.json()["run_id"])
    assert run["job_state"] == "succeeded"
    assert run["search_completion"] == "complete"
    assert run["runtime_library_versions"]["shapely"]
    assert run["versions_snapshot"]["cost_catalog"]["id"] == version_id
    assert run["optimality_scope"] == "candidate_reranking"
    assert run["alternatives"]
    first = run["alternatives"][0]
    assert first["cost_breakdown"]["status"] == "complete"
    assert first["quantity_items"]
    assert first["segments"]
    assert first["metrics"]["waypoint_count"] == 1
    assert first["validation_report"]["explanations"]

    events = client.get(f"/api/v1/runs/{run['id']}/events").json()
    reconnect = client.get(
        f"/api/v1/runs/{run['id']}/events/stream",
        headers={"Last-Event-ID": str(events[-2]["sequence"])},
    )
    assert reconnect.status_code == 200
    assert f"id: {events[-1]['sequence']}" in reconnect.text
    assert "event: run.completed" in reconnect.text

    assert _compute_route(str(run["id"]))["status"] == "already_finished"

    cached_accepted = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    cached = _wait(client, cached_accepted.json()["run_id"])
    assert cached["cache_info"]["hit"] is True
    assert cached["alternatives"][0]["geometry_hash"] == first["geometry_hash"]

    revised_catalog = client.post(
        f"/api/v1/cost-catalogs/{catalog['id']}/versions",
        json={"definition": _catalog(2, "6")},
        headers={"If-Match": "1"},
    )
    assert revised_catalog.status_code == 201
    assert (
        revised_catalog.json()["versions"][1]["definition_hash"]
        != catalog["versions"][0]["definition_hash"]
    )
    revised_payload = {
        **payload,
        "cost_catalog_version_id": revised_catalog.json()["versions"][1]["id"],
    }
    revised_scenario = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        json=revised_payload,
        headers={"If-Match": "1"},
    )
    assert revised_scenario.status_code == 201
    changed_accepted = client.post(
        f"/api/v1/scenario-revisions/{revised_scenario.json()['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    changed = _wait(client, changed_accepted.json()["run_id"])
    assert changed["cache_info"]["hit"] is False
    assert changed["cache_info"]["key"] != run["cache_info"]["key"]
    assert changed["versions_snapshot"]["cost_catalog"]["revision"] == 2


@pytest.mark.integration
def test_stale_lease_recovery_and_queue_overload(monkeypatch: pytest.MonkeyPatch) -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"M3 recovery {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios", json={"name": "Recovery"}
    ).json()
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        json={
            "entry_point_wgs84": [37.61, 55.752],
            "goal_point_wgs84": [37.612, 55.752],
            "explicit_assumptions": ["SYNTHETIC_ONLY"],
        },
        headers={"If-Match": "0"},
    ).json()
    accepted = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    run = _wait(client, accepted.json()["run_id"])
    with SessionLocal() as session:
        record = session.get(CalculationRun, UUID(str(run["id"])))
        assert record is not None
        job = session.get(Job, record.job_id)
        assert job is not None
        record.job_state = "running"
        record.phase = "search"
        job.state = "running"
        job.phase = "search"
        job.attempt = 1
        job.lease_owner = "crashed-worker"
        job.lease_expires_at = datetime.now(UTC) - timedelta(seconds=1)
        session.commit()
    recovery = _recover_stale_route_jobs()
    assert recovery["recovered"] == 1
    with SessionLocal() as session:
        job = session.get(Job, accepted.json()["job_id"])
        assert job is not None
        assert job.state == "queued"
        assert job.lease_owner is None
        assert session.query(OutboxEvent).filter_by(aggregate_id=UUID(str(run["id"]))).count() >= 2

    monkeypatch.setattr(
        run_services,
        "get_settings",
        lambda: SimpleNamespace(max_queued_route_jobs=0),
    )
    overloaded = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert overloaded.status_code == 429
    assert overloaded.json()["detail"]["code"] == "QUEUE_OVERLOADED"
