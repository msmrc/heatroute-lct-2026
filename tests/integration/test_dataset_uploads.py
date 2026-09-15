import hashlib
import json
import time
from pathlib import Path
from uuid import uuid4

import numpy as np
import pytest
from fastapi.testclient import TestClient
from pyogrio.raw import write
from shapely import to_wkb
from shapely.geometry import Point

from heatroute.api.app import create_app


def wait_for_import(client: TestClient, status_url: str) -> dict[str, object]:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        response = client.get(status_url)
        assert response.status_code == 200
        body = response.json()
        if body["state"] in {"mapping_required", "rejected", "failed"}:
            return body
        time.sleep(0.1)
    pytest.fail("dataset import did not reach an inspection terminal state")


def wait_for_import_states(
    client: TestClient,
    status_url: str,
    terminal_states: set[str],
) -> dict[str, object]:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        response = client.get(status_url)
        assert response.status_code == 200
        body = response.json()
        if body["state"] in terminal_states:
            return body
        time.sleep(0.1)
    pytest.fail(f"dataset import did not reach one of {sorted(terminal_states)}")


@pytest.mark.integration
def test_dataset_upload_persists_provenance_and_deduplicates_raw_bytes() -> None:
    client = TestClient(create_app())
    project_response = client.post(
        "/api/v1/projects",
        json={
            "name": f"Dataset upload {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "provided",
        },
    )
    assert project_response.status_code == 201
    project_id = project_response.json()["id"]
    raw_bytes = Path("examples/raw_buildings.demo.geojson").read_bytes()

    first = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={
            "dataset_name": "Buildings",
            "purpose": "obstacles",
            "license_note": "integration fixture",
        },
        files={"file": ("../../district.geojson", raw_bytes, "application/geo+json")},
    )
    assert first.status_code == 202, first.text
    accepted = first.json()
    assert accepted["job_state"] in {"queued", "running", "succeeded"}
    assert accepted["dataset_version"]["status"] in {"uploaded", "mapping_required"}
    assert accepted["dataset_version"]["version"] == 1
    assert accepted["dataset_version"]["source_name"] == "district.geojson"
    assert accepted["artifact"]["sha256"] == hashlib.sha256(raw_bytes).hexdigest()
    assert accepted["artifact"]["size_bytes"] == len(raw_bytes)

    import_status = wait_for_import(client, accepted["status_url"])
    assert import_status["state"] == "mapping_required"
    assert import_status["phase"] == "inspection_complete"
    inspection = client.get(f"/api/v1/imports/{accepted['import_id']}/inspection")
    assert inspection.status_code == 200
    report = inspection.json()
    assert report["import_state"] == "mapping_required"
    assert report["layers"][0]["crs"] == "EPSG:4326"
    assert report["publish_blockers"][0]["code"] == "MAPPING_REQUIRED"
    assert report["source_references"][0]["sha256"] == hashlib.sha256(raw_bytes).hexdigest()
    mapping = client.put(
        f"/api/v1/imports/{accepted['import_id']}/mapping",
        json={
            "profile_name": "Buildings mapping",
            "layer_name": report["layers"][0]["name"],
            "source_namespace": "buildings",
            "target_kind": "building",
            "source_id_field": "BID",
            "source_crs": "EPSG:4326",
            "fields": {
                "building_role": {
                    "transforms": [{"op": "constant", "value": "existing"}]
                },
                "external_name": {
                    "source_field": "NAME_RU",
                    "transforms": [{"op": "trim"}],
                },
                "height_m": {
                    "source_field": "HEIGHT_CM",
                    "transforms": [
                        {"op": "parse_decimal"},
                        {
                            "op": "unit_convert",
                            "from_unit": "cm",
                            "to_unit": "m",
                            "factor": "0.01",
                        },
                    ],
                },
            },
            "missing_policy": "report_and_keep_null",
        },
    )
    assert mapping.status_code == 200, mapping.text
    assert mapping.json()["revision"] == 1
    assert mapping.json()["dataset_version_status"] == "ready_to_validate"
    assert mapping.json()["import_state"] == "ready_to_validate"
    versions = client.get(f"/api/v1/projects/{project_id}/dataset-versions")
    assert versions.status_code == 200
    assert versions.json()[0]["status"] == "ready_to_validate"

    hidden_features = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{accepted['dataset_version']['id']}/features"
    )
    assert hidden_features.status_code == 409
    validation = client.post(f"/api/v1/imports/{accepted['import_id']}/validate")
    assert validation.status_code == 202, validation.text
    validated = wait_for_import_states(
        client,
        accepted["status_url"],
        {"ready_to_publish", "needs_review", "failed"},
    )
    assert validated["state"] == "ready_to_publish", validated
    validation_report = client.get(f"/api/v1/imports/{accepted['import_id']}/report")
    assert validation_report.status_code == 200
    assert validation_report.json()["stage"] == "validation"
    assert validation_report.json()["counts"] == {
        "total": 1,
        "read": 1,
        "accepted": 1,
        "quarantined": 0,
        "rejected": 0,
    }
    assert validation_report.json()["repairs"] == []
    assert validation_report.json()["crs_diagnostics"][0]["allow_ballpark"] is False
    assert validation_report.json()["coverage_diagnostics"][0]["code"] == "COVERAGE_UNKNOWN"

    publication = client.post(
        f"/api/v1/imports/{accepted['import_id']}/publish",
        json={},
    )
    assert publication.status_code == 202, publication.text
    published = wait_for_import_states(
        client,
        accepted["status_url"],
        {"published", "failed"},
    )
    assert published["state"] == "published", published
    canonical = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{accepted['dataset_version']['id']}/features"
    )
    assert canonical.status_code == 200, canonical.text
    assert len(canonical.json()) == 1
    feature = canonical.json()[0]
    assert feature["source_id"] == "BLD-RAW-001"
    assert feature["kind"] == "building"
    assert feature["source_type"] == "user"
    assert feature["attributes"]["height_m"] == 12.0
    assert feature["geometry"]["type"] == "Polygon"
    provenance = client.get(f"/api/v1/features/{feature['id']}")
    assert provenance.status_code == 200
    assert provenance.json()["raw_properties"]["BID"] == "BLD-RAW-001"
    replay_publication = client.post(
        f"/api/v1/imports/{accepted['import_id']}/publish",
        json={},
    )
    assert replay_publication.status_code == 202
    assert replay_publication.json()["state"] == "published"
    assert (
        len(
            client.get(
                f"/api/v1/projects/{project_id}/dataset-versions/"
                f"{accepted['dataset_version']['id']}/features"
            ).json()
        )
        == 1
    )

    second_payload = json.loads(raw_bytes)
    original_feature = second_payload["features"][0]
    original_feature["properties"] = {
        "OBJECT_CODE": original_feature["properties"]["BID"],
        "TITLE": original_feature["properties"]["NAME_RU"],
        "HEIGHT_MM": "12000",
    }
    second_bytes = json.dumps(second_payload, separators=(",", ":")).encode()
    second = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={"dataset_id": accepted["dataset_id"]},
        files={"file": ("district.geojson", second_bytes, "application/geo+json")},
    )
    assert second.status_code == 202, second.text
    replay = second.json()
    assert replay["dataset_version"]["version"] == 2
    assert wait_for_import(client, replay["status_url"])["state"] == "mapping_required"
    second_report = client.get(f"/api/v1/imports/{replay['import_id']}/report").json()
    second_mapping = client.put(
        f"/api/v1/imports/{replay['import_id']}/mapping",
        json={
            "profile_name": "Buildings alternate schema",
            "layer_name": second_report["layers"][0]["name"],
            "source_namespace": "buildings",
            "target_kind": "building",
            "source_id_field": "OBJECT_CODE",
            "source_crs": "EPSG:4326",
            "fields": {
                "building_role": {
                    "transforms": [{"op": "constant", "value": "existing"}]
                },
                "external_name": {"source_field": "TITLE", "transforms": [{"op": "trim"}]},
                "height_m": {
                    "source_field": "HEIGHT_MM",
                    "transforms": [
                        {"op": "parse_decimal"},
                        {
                            "op": "unit_convert",
                            "from_unit": "mm",
                            "to_unit": "m",
                            "factor": "0.001",
                        },
                    ],
                },
            },
            "missing_policy": "report_and_keep_null",
        },
    )
    assert second_mapping.status_code == 200, second_mapping.text
    assert client.post(f"/api/v1/imports/{replay['import_id']}/validate").status_code == 202
    assert wait_for_import_states(
        client, replay["status_url"], {"ready_to_publish", "failed"}
    )["state"] == "ready_to_publish"
    assert client.post(f"/api/v1/imports/{replay['import_id']}/publish", json={}).status_code == 202
    assert wait_for_import_states(
        client, replay["status_url"], {"published", "failed"}
    )["state"] == "published"

    diff = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{replay['dataset_version']['id']}/diff"
    )
    assert diff.status_code == 200, diff.text
    assert diff.json()["previous_dataset_version_id"] == accepted["dataset_version"]["id"]
    assert diff.json()["unchanged"] == 1
    assert diff.json()["added"] == []
    assert diff.json()["changed"] == []
    assert diff.json()["deleted"] == []
    assert diff.json()["unmapped_layers"] == []
    old_features = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{accepted['dataset_version']['id']}/features"
    ).json()
    assert old_features == [feature]

    layers = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{replay['dataset_version']['id']}/layers"
    )
    assert layers.status_code == 200
    assert layers.json()[0]["status"] == "published"
    assert layers.json()[0]["mapped_kind"] == "building"
    quality = client.get(f"/api/v1/projects/{project_id}/quality")
    assert quality.status_code == 200
    assert quality.json()["coverage_state"] == "unknown"
    assert quality.json()["findings"][0]["code"] == "COVERAGE_UNKNOWN"


@pytest.mark.integration
def test_duplicate_ids_are_quarantined_and_publish_requires_limitations() -> None:
    client = TestClient(create_app())
    project_id = client.post(
        "/api/v1/projects",
        json={
            "name": f"Quarantine upload {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "provided",
        },
    ).json()["id"]
    features = []
    for source_id, longitude in (("GOOD", 37.620), ("DUP", 37.622), ("DUP", 37.624)):
        features.append(
            {
                "type": "Feature",
                "properties": {"id": source_id, "name": source_id},
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                        [
                            [longitude, 55.750],
                            [longitude + 0.001, 55.750],
                            [longitude + 0.001, 55.751],
                            [longitude, 55.751],
                            [longitude, 55.750],
                        ]
                    ],
                },
            }
        )
    raw_bytes = json.dumps(
        {"type": "FeatureCollection", "features": features},
        separators=(",", ":"),
    ).encode()
    accepted = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={"dataset_name": "Duplicate buildings"},
        files={"file": ("duplicates.geojson", raw_bytes, "application/geo+json")},
    ).json()
    assert wait_for_import(client, accepted["status_url"])["state"] == "mapping_required"
    report = client.get(f"/api/v1/imports/{accepted['import_id']}/report").json()
    mapping = client.put(
        f"/api/v1/imports/{accepted['import_id']}/mapping",
        json={
            "profile_name": "Duplicate mapping",
            "layer_name": report["layers"][0]["name"],
            "target_kind": "building",
            "source_id_field": "id",
            "source_crs": "EPSG:4326",
            "fields": {
                "building_role": {
                    "transforms": [{"op": "constant", "value": "existing"}]
                },
                "external_name": {"source_field": "name"},
            },
            "missing_policy": "quarantine",
        },
    )
    assert mapping.status_code == 200, mapping.text
    assert client.post(f"/api/v1/imports/{accepted['import_id']}/validate").status_code == 202
    validated = wait_for_import_states(
        client,
        accepted["status_url"],
        {"needs_review", "ready_to_publish", "failed"},
    )
    assert validated["state"] == "needs_review", validated
    validation_report = client.get(f"/api/v1/imports/{accepted['import_id']}/report").json()
    assert validation_report["counts"] == {
        "total": 3,
        "read": 3,
        "accepted": 1,
        "quarantined": 2,
        "rejected": 0,
    }
    assert (
        sum(issue["code"] == "DUPLICATE_SOURCE_ID" for issue in validation_report["warnings"]) == 2
    )

    blocked = client.post(
        f"/api/v1/imports/{accepted['import_id']}/publish",
        json={"confirm_quarantine": True},
    )
    assert blocked.status_code == 409
    publication = client.post(
        f"/api/v1/imports/{accepted['import_id']}/publish",
        json={
            "confirm_quarantine": True,
            "coverage_limitations": "Two duplicate source IDs are excluded; their area is unknown.",
        },
    )
    assert publication.status_code == 202, publication.text
    published = wait_for_import_states(
        client,
        accepted["status_url"],
        {"published", "failed"},
    )
    assert published["state"] == "published", published
    canonical = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{accepted['dataset_version']['id']}/features"
    )
    assert canonical.status_code == 200
    assert [feature["source_id"] for feature in canonical.json()] == ["GOOD"]


@pytest.mark.integration
def test_network_edge_with_missing_endpoints_is_quarantined() -> None:
    client = TestClient(create_app())
    project_id = client.post(
        "/api/v1/projects",
        json={
            "name": f"Topology upload {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "provided",
        },
    ).json()["id"]
    raw_bytes = json.dumps(
        {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {
                        "edge_id": "EDGE-1",
                        "from_id": "NODE-MISSING-A",
                        "to_id": "NODE-MISSING-B",
                        "network_id": "NET-1",
                    },
                    "geometry": {
                        "type": "LineString",
                        "coordinates": [[37.62, 55.75], [37.621, 55.751]],
                    },
                }
            ],
        },
        separators=(",", ":"),
    ).encode()
    accepted = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={"dataset_name": "Edges"},
        files={"file": ("edges.geojson", raw_bytes, "application/geo+json")},
    ).json()
    assert wait_for_import(client, accepted["status_url"])["state"] == "mapping_required"
    report = client.get(f"/api/v1/imports/{accepted['import_id']}/report").json()
    mapping = client.put(
        f"/api/v1/imports/{accepted['import_id']}/mapping",
        json={
            "profile_name": "Edges mapping",
            "layer_name": report["layers"][0]["name"],
            "source_namespace": "network_edges",
            "target_kind": "network_edge",
            "source_id_field": "edge_id",
            "source_crs": "EPSG:4326",
            "fields": {
                "circuit": {"transforms": [{"op": "constant", "value": "unknown"}]},
                "from_node_id": {"source_field": "from_id"},
                "status": {"transforms": [{"op": "constant", "value": "unknown"}]},
                "to_node_id": {"source_field": "to_id"},
                "source_network_id": {"source_field": "network_id"},
            },
            "missing_policy": "quarantine",
        },
    )
    assert mapping.status_code == 200, mapping.text
    assert client.post(f"/api/v1/imports/{accepted['import_id']}/validate").status_code == 202
    validated = wait_for_import_states(
        client, accepted["status_url"], {"needs_review", "failed"}
    )
    assert validated["state"] == "needs_review", validated
    validation_report = client.get(f"/api/v1/imports/{accepted['import_id']}/report").json()
    assert validation_report["counts"]["accepted"] == 0
    assert validation_report["counts"]["quarantined"] == 1
    references = [
        warning
        for warning in validation_report["warnings"]
        if warning["code"] == "REFERENCE_NOT_FOUND"
    ]
    assert {warning["field"] for warning in references} == {"from_node_id", "to_node_id"}
    assert len(validation_report["topology_diagnostics"]) == 2


@pytest.mark.integration
def test_multilayer_geopackage_preserves_unselected_layer_provenance(tmp_path: Path) -> None:
    source = tmp_path / "network.gpkg"
    write(
        source,
        np.array([to_wkb(Point(37.62, 55.75))], dtype=object),
        [np.array(["NODE-1"], dtype=object), np.array(["junction"], dtype=object)],
        ["source_id", "node_type"],
        layer="nodes",
        driver="GPKG",
        geometry_type="Point",
        crs="EPSG:4326",
    )
    write(
        source,
        np.array([to_wkb(Point(37.63, 55.76))], dtype=object),
        [np.array(["UNSELECTED-1"], dtype=object)],
        ["source_id"],
        layer="other_points",
        driver="GPKG",
        geometry_type="Point",
        crs="EPSG:4326",
        append=True,
    )
    client = TestClient(create_app())
    project_id = client.post(
        "/api/v1/projects",
        json={
            "name": f"Multi-layer upload {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "provided",
        },
    ).json()["id"]
    upload = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={"dataset_name": "Network layers"},
        files={"file": ("network.gpkg", source.read_bytes(), "application/geopackage+sqlite3")},
    )
    assert upload.status_code == 202, upload.text
    accepted = upload.json()
    assert wait_for_import(client, accepted["status_url"])["state"] == "mapping_required"
    report = client.get(f"/api/v1/imports/{accepted['import_id']}/report").json()
    assert {layer["name"] for layer in report["layers"]} == {"nodes", "other_points"}
    mapping = client.put(
        f"/api/v1/imports/{accepted['import_id']}/mapping",
        json={
            "profile_name": "Nodes only",
            "layer_name": "nodes",
            "source_namespace": "network_nodes",
            "target_kind": "network_node",
            "source_id_field": "source_id",
            "source_crs": "EPSG:4326",
            "fields": {
                "node_type": {"source_field": "node_type"},
                "source_network_id": {
                    "transforms": [{"op": "constant", "value": "NET-1"}]
                },
                "circuit": {"transforms": [{"op": "constant", "value": "unknown"}]},
                "connection_permission": {
                    "transforms": [{"op": "constant", "value": "unknown"}]
                },
            },
            "missing_policy": "quarantine",
        },
    )
    assert mapping.status_code == 200, mapping.text
    assert client.post(f"/api/v1/imports/{accepted['import_id']}/validate").status_code == 202
    assert wait_for_import_states(
        client, accepted["status_url"], {"ready_to_publish", "failed"}
    )["state"] == "ready_to_publish"
    publication = client.post(
        f"/api/v1/imports/{accepted['import_id']}/publish", json={}
    )
    assert publication.status_code == 202
    assert wait_for_import_states(
        client, accepted["status_url"], {"published", "failed"}
    )["state"] == "published"
    layers = client.get(
        f"/api/v1/projects/{project_id}/dataset-versions/"
        f"{accepted['dataset_version']['id']}/layers"
    ).json()
    states = {layer["name"]: layer["status"] for layer in layers}
    assert states == {"nodes": "published", "other_points": "not_selected"}


@pytest.mark.integration
def test_dataset_upload_rejects_invalid_shapefile_archive_during_inspection() -> None:
    client = TestClient(create_app())
    project_id = client.post(
        "/api/v1/projects",
        json={"name": f"Rejected upload {uuid4()}", "source_mode": "provided"},
    ).json()["id"]

    response = client.post(
        f"/api/v1/projects/{project_id}/datasets/uploads",
        data={"dataset_name": "Archive"},
        files={"file": ("unsafe.zip", b"not a safe archive", "application/zip")},
    )

    assert response.status_code == 202
    accepted = response.json()
    terminal = wait_for_import_states(
        client, accepted["status_url"], {"rejected", "failed"}
    )
    assert terminal["state"] == "rejected"
    assert terminal["result"]["code"] == "INSPECTION_REJECTED"
