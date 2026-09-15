import json
import time
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app


def _definition() -> dict[str, object]:
    path = Path(__file__).parents[2] / "examples" / "rule_profile.demo.json"
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.mark.integration
def test_rule_profile_versions_are_immutable_and_selected_by_scenario() -> None:
    client = TestClient(create_app())
    project_response = client.post(
        "/api/v1/projects",
        json={
            "name": f"Rule profile project {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    )
    assert project_response.status_code == 201
    project_id = project_response.json()["id"]

    definition = _definition()
    created = client.post(
        f"/api/v1/projects/{project_id}/rule-profiles",
        json={"name": definition["name"], "definition": definition},
    )
    assert created.status_code == 201
    profile = created.json()
    assert profile["current_revision"] == 1
    assert profile["versions"][0]["definition_hash"]
    first_version_id = profile["versions"][0]["id"]

    missing_if_match = client.post(
        f"/api/v1/rule-profiles/{profile['id']}/versions",
        json={"definition": {**definition, "version": 2}},
    )
    assert missing_if_match.status_code == 428

    revision_two = {**definition, "version": 2, "status": "draft"}
    revised = client.post(
        f"/api/v1/rule-profiles/{profile['id']}/versions",
        json={"definition": revision_two},
        headers={"If-Match": 'W/"1"'},
    )
    assert revised.status_code == 201
    assert revised.json()["current_revision"] == 2
    assert [row["revision"] for row in revised.json()["versions"]] == [1, 2]

    stale = client.post(
        f"/api/v1/rule-profiles/{profile['id']}/versions",
        json={"definition": {**definition, "version": 2}},
        headers={"If-Match": "1"},
    )
    assert stale.status_code == 412

    listed = client.get(f"/api/v1/projects/{project_id}/rule-profiles")
    assert listed.status_code == 200
    assert [row["id"] for row in listed.json()] == [profile["id"]]

    scenario = client.post(
        f"/api/v1/projects/{project_id}/scenarios",
        json={"name": "Pinned rule profile scenario"},
    ).json()
    scenario_definition = {
        "input_mode": "point_to_point_demo",
        "entry_point_wgs84": [37.61, 55.752],
        "goal_point_wgs84": [37.64, 55.752],
        "forbidden_rectangles_wgs84": [],
        "corridor_width_m": 8,
        "explicit_assumptions": ["synthetic fixture"],
        "rule_profile_version_id": first_version_id,
        "validation_mode": "strict",
    }
    scenario_revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        json=scenario_definition,
        headers={"If-Match": "0"},
    )
    assert scenario_revision.status_code == 201
    assert (
        scenario_revision.json()["input_snapshot"]["rule_profile_version_id"]
        == first_version_id
    )
    preflight = client.post(
        f"/api/v1/scenario-revisions/{scenario_revision.json()['id']}/preflight"
    )
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is True
    assert "RULE_PROFILE_VERSION_INVALID" not in {
        finding["code"] for finding in preflight.json()["findings"]
    }

    run_response = client.post(
        f"/api/v1/scenario-revisions/{scenario_revision.json()['id']}/runs",
        json={},
        headers={"Idempotency-Key": str(uuid4())},
    )
    assert run_response.status_code == 202
    deadline = time.monotonic() + 20
    run = None
    while time.monotonic() < deadline:
        candidate = client.get(f"/api/v1/runs/{run_response.json()['run_id']}").json()
        if candidate["job_state"] in {"succeeded", "partial", "failed", "cancelled"}:
            run = candidate
            break
        time.sleep(0.1)
    assert run is not None
    assert run["job_state"] == "succeeded"
    rule_report = run["alternatives"][0]["validation_report"]["rule_profile"]
    assert rule_report["definition_id"] == definition["id"]
    assert rule_report["definition_version"] == 1


@pytest.mark.integration
def test_rule_profile_rejects_unsupported_rules_and_foreign_version() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"Rule validation project {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    definition = _definition()
    bad_definition = {
        **definition,
        "rules": [{**definition["rules"][0], "type": "python_expression"}],
    }
    rejected = client.post(
        f"/api/v1/projects/{project['id']}/rule-profiles",
        json={"name": definition["name"], "definition": bad_definition},
    )
    assert rejected.status_code == 422
    assert rejected.json()["detail"]["code"] == "INVALID_RULE_PROFILE"

    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios",
        json={"name": "Missing profile reference"},
    ).json()
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        headers={"If-Match": "0"},
        json={
            "input_mode": "point_to_point_demo",
            "entry_point_wgs84": [37.61, 55.752],
            "goal_point_wgs84": [37.64, 55.752],
            "rule_profile_version_id": str(uuid4()),
            "explicit_assumptions": ["synthetic fixture"],
        },
    ).json()
    preflight = client.post(f"/api/v1/scenario-revisions/{revision['id']}/preflight")
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is False
    assert "RULE_PROFILE_VERSION_NOT_FOUND" in {
        finding["code"] for finding in preflight.json()["findings"]
    }
