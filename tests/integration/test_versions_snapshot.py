import json
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from geoalchemy2.shape import from_shape
from shapely.geometry import Point, box

from heatroute.api.app import create_app
from heatroute.db import SessionLocal
from heatroute.models import (
    CanonicalFeature,
    Dataset,
    DatasetImport,
    DatasetVersion,
    Job,
    StagingFeature,
)


def _scenario_payload(
    version_ids: list[str], *, rule_profile_version_id: str
) -> dict[str, object]:
    return {
        "input_mode": "point_to_point_demo",
        "entry_point_wgs84": [37.61, 55.752],
        "goal_point_wgs84": [37.64, 55.752],
        "corridor_width_m": 8,
        "selected_dataset_version_ids": version_ids,
        "planning_date": "2026-09-08",
        "rule_profile_version_id": rule_profile_version_id,
        "explicit_assumptions": ["synthetic version snapshot fixture"],
        "connection_candidate_ids": ["C1"],
        "requested_load_kw": 250,
    }


def _wait_for_run(client: TestClient, run_id: str) -> dict[str, object]:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        run = client.get(f"/api/v1/runs/{run_id}").json()
        if run["job_state"] in {"succeeded", "partial", "failed", "cancelled"}:
            return run
        time.sleep(0.1)
    pytest.fail("run did not reach a terminal state")


@pytest.mark.integration
def test_published_dataset_selection_is_pinned_in_calculation_run() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"Version snapshot project {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    rule_definition = json.loads(
        (Path(__file__).parents[2] / "examples" / "rule_profile.demo.json").read_text(
            encoding="utf-8"
        )
    )
    rule_profile = client.post(
        f"/api/v1/projects/{project['id']}/rule-profiles",
        json={"name": rule_definition["name"], "definition": rule_definition},
    ).json()
    rule_profile_version_id = rule_profile["versions"][0]["id"]
    with SessionLocal() as session:
        dataset = Dataset(
            workspace_id=UUID(project["workspace_id"]),
            project_id=UUID(project["id"]),
            name="Published context",
            purpose="routing",
        )
        session.add(dataset)
        session.flush()
        published = DatasetVersion(
            dataset_id=dataset.id,
            version=1,
            status="published",
            source_type="geojson",
            source_name="fixture.geojson",
            raw_hashes=["a" * 64],
            adapter_name="test",
            adapter_version="1",
            working_crs="EPSG:32637",
            transform_hash="b" * 64,
            published_at=datetime.now(UTC),
        )
        draft = DatasetVersion(
            dataset_id=dataset.id,
            version=2,
            status="uploaded",
            source_type="geojson",
            source_name="fixture-v2.geojson",
            raw_hashes=["c" * 64],
            working_crs="EPSG:32637",
        )
        session.add_all((published, draft))
        session.flush()
        import_job = Job(
            workspace_id=UUID(project["workspace_id"]),
            kind="dataset_import",
            state="succeeded",
            phase="published",
            payload={},
        )
        session.add(import_job)
        session.flush()
        dataset_import = DatasetImport(
            workspace_id=UUID(project["workspace_id"]),
            project_id=UUID(project["id"]),
            dataset_version_id=published.id,
            job_id=import_job.id,
            state="published",
        )
        session.add(dataset_import)
        session.flush()
        building_geometry = from_shape(
            box(37.623, 55.748, 37.628, 55.756), srid=4326
        )
        staged = StagingFeature(
            workspace_id=UUID(project["workspace_id"]),
            dataset_import_id=dataset_import.id,
            source_row=1,
            source_id="B1",
            source_layer="buildings",
            target_kind="building",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes={"building_role": "existing"},
            geometry_wgs84=building_geometry,
        )
        session.add(staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=UUID(project["workspace_id"]),
                project_id=UUID(project["id"]),
                dataset_version_id=published.id,
                staging_feature_id=staged.id,
                logical_id=uuid4(),
                kind="building",
                source_id="B1",
                source_layer="buildings",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes={"building_role": "existing"},
                geometry_wgs84=building_geometry,
            )
        )
        node_geometry = from_shape(Point(37.61, 55.752), srid=4326)
        node_staged = StagingFeature(
            workspace_id=UUID(project["workspace_id"]),
            dataset_import_id=dataset_import.id,
            source_row=2,
            source_id="N1",
            source_layer="network_nodes",
            target_kind="network_node",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes={
                "node_type": "connection",
                "source_network_id": "heat-1",
                "circuit": "supply",
                "connection_permission": "allowed",
            },
            geometry_wgs84=node_geometry,
        )
        session.add(node_staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=UUID(project["workspace_id"]),
                project_id=UUID(project["id"]),
                dataset_version_id=published.id,
                staging_feature_id=node_staged.id,
                logical_id=uuid4(),
                kind="network_node",
                source_id="N1",
                source_layer="network_nodes",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes={
                    "node_type": "connection",
                    "source_network_id": "heat-1",
                    "circuit": "supply",
                    "connection_permission": "allowed",
                },
                geometry_wgs84=node_geometry,
            )
        )
        target_geometry = from_shape(
            box(37.6419, 55.7518, 37.6423, 55.7522), srid=4326
        )
        target_staged = StagingFeature(
            workspace_id=UUID(project["workspace_id"]),
            dataset_import_id=dataset_import.id,
            source_row=4,
            source_id="TARGET",
            source_layer="buildings",
            target_kind="building",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes={"building_role": "target"},
            geometry_wgs84=target_geometry,
        )
        session.add(target_staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=UUID(project["workspace_id"]),
                project_id=UUID(project["id"]),
                dataset_version_id=published.id,
                staging_feature_id=target_staged.id,
                logical_id=uuid4(),
                kind="building",
                source_id="TARGET",
                source_layer="buildings",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes={"building_role": "target"},
                geometry_wgs84=target_geometry,
            )
        )
        gate_geometry = from_shape(
            box(37.6413, 55.75165, 37.6422, 55.75235), srid=4326
        )
        gate_attributes = {
            "target_building_id": "TARGET",
            "entry_point": [37.642, 55.752],
            "max_connector_length_m": 60,
        }
        gate_staged = StagingFeature(
            workspace_id=UUID(project["workspace_id"]),
            dataset_import_id=dataset_import.id,
            source_row=5,
            source_id="G1",
            source_layer="entry_gates",
            target_kind="entry_gate",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes=gate_attributes,
            geometry_wgs84=gate_geometry,
        )
        session.add(gate_staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=UUID(project["workspace_id"]),
                project_id=UUID(project["id"]),
                dataset_version_id=published.id,
                staging_feature_id=gate_staged.id,
                logical_id=uuid4(),
                kind="entry_gate",
                source_id="G1",
                source_layer="entry_gates",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes=gate_attributes,
                geometry_wgs84=gate_geometry,
            )
        )
        candidate_staged = StagingFeature(
            workspace_id=UUID(project["workspace_id"]),
            dataset_import_id=dataset_import.id,
            source_row=3,
            source_id="C1",
            source_layer="connection_candidates",
            target_kind="connection_candidate",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes={
                "network_node_id": "N1",
                "permission": "allowed",
                "capacity_basis": "net_available",
                "available_capacity_kw": 500,
            },
            geometry_wgs84=node_geometry,
        )
        session.add(candidate_staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=UUID(project["workspace_id"]),
                project_id=UUID(project["id"]),
                dataset_version_id=published.id,
                staging_feature_id=candidate_staged.id,
                logical_id=uuid4(),
                kind="connection_candidate",
                source_id="C1",
                source_layer="connection_candidates",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes={
                    "network_node_id": "N1",
                    "permission": "allowed",
                    "capacity_basis": "net_available",
                    "available_capacity_kw": 500,
                },
                geometry_wgs84=node_geometry,
            )
        )
        session.commit()
        published_id = str(published.id)
        draft_id = str(draft.id)

    scenario = client.post(
        f"/api/v1/projects/{project['id']}/scenarios",
        json={"name": "Pinned datasets"},
    ).json()
    revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        headers={"If-Match": "0"},
        json=_scenario_payload(
            [published_id], rule_profile_version_id=rule_profile_version_id
        ),
    ).json()
    preflight = client.post(f"/api/v1/scenario-revisions/{revision['id']}/preflight")
    assert preflight.status_code == 200
    assert preflight.json()["ready"] is True

    accepted = client.post(
        f"/api/v1/scenario-revisions/{revision['id']}/runs",
        headers={"Idempotency-Key": str(uuid4())},
        json={},
    )
    assert accepted.status_code == 202
    run = _wait_for_run(client, accepted.json()["run_id"])
    assert run["job_state"] == "succeeded"
    manifest = run["versions_snapshot"]
    assert manifest["scenario_input_hash"] == revision["input_hash"]
    assert manifest["datasets"][0]["id"] == published_id
    assert manifest["datasets"][0]["raw_hashes"] == ["a" * 64]
    assert manifest["datasets"][0]["selection_hash"]
    assert manifest["manifest_hash"]
    assert manifest["candidate_screening"]["selected"][0]["source_id"] == "C1"
    assert manifest["candidate_screening"]["rejected"] == []
    assert run["alternatives"][0]["metrics"]["route_length_m"] > 1_900
    assert (
        run["alternatives"][0]["validation_report"]["rule_profile"]["findings"]
        == []
    )

    building_revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        headers={"If-Match": "1"},
        json={
            "input_mode": "building_to_network",
            "entry_point_wgs84": [37.642, 55.752],
            "target_building_id": "TARGET",
            "entry_gate_id": "G1",
            "corridor_width_m": 4,
            "construction_methods": ["open_trench"],
            "selected_dataset_version_ids": [published_id],
            "planning_date": "2026-09-08",
            "rule_profile_version_id": rule_profile_version_id,
            "explicit_assumptions": ["synthetic version snapshot fixture"],
            "connection_candidate_ids": ["C1"],
            "requested_load_kw": 250,
        },
    )
    assert building_revision.status_code == 201, building_revision.text
    building_preflight = client.post(
        f"/api/v1/scenario-revisions/{building_revision.json()['id']}/preflight"
    )
    assert building_preflight.status_code == 200
    assert building_preflight.json()["ready"] is True, building_preflight.json()
    building_accepted = client.post(
        f"/api/v1/scenario-revisions/{building_revision.json()['id']}/runs",
        headers={"Idempotency-Key": str(uuid4())},
        json={},
    )
    assert building_accepted.status_code == 202, building_accepted.text
    building_run = _wait_for_run(client, building_accepted.json()["run_id"])
    assert building_run["job_state"] == "succeeded", building_run
    building_alternative = building_run["alternatives"][0]
    assert building_alternative["metrics"]["connection_candidate_id"] == "C1"
    assert building_alternative["centerline_wgs84"]["coordinates"][-1] == pytest.approx(
        [37.642, 55.752], abs=1e-7
    )
    assert building_alternative["validation_report"]["rule_profile"]["findings"] == []
    manual_valid = client.post(
        f"/api/v1/scenario-revisions/{building_revision.json()['id']}/validate-route",
        json={"centerline_wgs84": building_alternative["centerline_wgs84"]},
    )
    assert manual_valid.status_code == 200, manual_valid.text
    assert manual_valid.json()["geometry_status"] == "valid_in_model"
    manual_invalid = client.post(
        f"/api/v1/scenario-revisions/{building_revision.json()['id']}/validate-route",
        json={
            "centerline_wgs84": {
                "type": "LineString",
                "coordinates": [
                    [37.61, 55.752],
                    [37.6413, 55.752],
                    [37.642, 55.752],
                ],
            }
        },
    )
    assert manual_invalid.status_code == 200, manual_invalid.text
    assert manual_invalid.json()["geometry_status"] == "invalid_in_model"
    building_conflict = next(
        finding
        for finding in manual_invalid.json()["validation_report"]["findings"]
        if finding["code"] == "BUILDING_CORRIDOR_INTERSECTION"
    )
    assert building_conflict["geometry_wgs84"]["type"] in {
        "Polygon",
        "MultiPolygon",
    }

    blocked_revision = client.post(
        f"/api/v1/scenarios/{scenario['id']}/revisions",
        headers={"If-Match": "2"},
        json=_scenario_payload([draft_id], rule_profile_version_id=rule_profile_version_id),
    ).json()
    blocked = client.post(
        f"/api/v1/scenario-revisions/{blocked_revision['id']}/preflight"
    )
    assert blocked.status_code == 200
    assert blocked.json()["ready"] is False
    assert "DATASET_VERSION_NOT_PUBLISHED" in {
        finding["code"] for finding in blocked.json()["findings"]
    }
