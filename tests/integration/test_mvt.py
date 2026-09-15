import math
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from geoalchemy2.shape import from_shape
from shapely.geometry import Point

from heatroute.api.app import create_app
from heatroute.db import SessionLocal
from heatroute.models import (
    CanonicalFeature,
    Dataset,
    DatasetImport,
    DatasetLayerVersion,
    DatasetVersion,
    Job,
    StagingFeature,
)


def _tile(lon: float, lat: float, zoom: int) -> tuple[int, int]:
    scale = 2**zoom
    x = int((lon + 180) / 360 * scale)
    latitude_radians = math.radians(lat)
    y = int((1 - math.asinh(math.tan(latitude_radians)) / math.pi) / 2 * scale)
    return x, y


@pytest.mark.integration
def test_published_layer_is_served_as_workspace_scoped_mvt_with_etag() -> None:
    client = TestClient(create_app())
    project = client.post(
        "/api/v1/projects",
        json={
            "name": f"MVT project {uuid4()}",
            "working_crs": "EPSG:32637",
            "crs_confirmed": True,
            "source_mode": "synthetic",
        },
    ).json()
    workspace_id = UUID(project["workspace_id"])
    project_id = UUID(project["id"])
    with SessionLocal() as session:
        dataset = Dataset(
            workspace_id=workspace_id,
            project_id=project_id,
            name="MVT features",
            purpose="map",
        )
        session.add(dataset)
        session.flush()
        version = DatasetVersion(
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
        session.add(version)
        session.flush()
        layer = DatasetLayerVersion(
            dataset_version_id=version.id,
            name="nodes",
            ordinal=0,
            geometry_type="Point",
            source_crs="EPSG:4326",
            mapped_kind="network_node",
            feature_count=1,
            status="published",
        )
        job = Job(
            workspace_id=workspace_id,
            kind="dataset_import",
            state="succeeded",
            phase="published",
            payload={},
        )
        session.add_all((layer, job))
        session.flush()
        dataset_import = DatasetImport(
            workspace_id=workspace_id,
            project_id=project_id,
            dataset_version_id=version.id,
            job_id=job.id,
            state="published",
        )
        session.add(dataset_import)
        session.flush()
        geometry = from_shape(Point(37.61, 55.75), srid=4326)
        staged = StagingFeature(
            workspace_id=workspace_id,
            dataset_import_id=dataset_import.id,
            source_row=1,
            source_id="N-1",
            source_layer="nodes",
            target_kind="network_node",
            status="accepted",
            issue_codes=[],
            raw_properties={},
            attributes={},
            geometry_wgs84=geometry,
        )
        session.add(staged)
        session.flush()
        session.add(
            CanonicalFeature(
                workspace_id=workspace_id,
                project_id=project_id,
                dataset_version_id=version.id,
                staging_feature_id=staged.id,
                logical_id=uuid4(),
                kind="network_node",
                source_id="N-1",
                source_layer="nodes",
                source_type="geojson",
                lifecycle_status="existing",
                quality_flags=[],
                raw_properties={},
                attributes={},
                geometry_wgs84=geometry,
            )
        )
        session.commit()
        layer_id = layer.id

    x, y = _tile(37.61, 55.75, 10)
    response = client.get(f"/api/v1/layers/{layer_id}/tiles/10/{x}/{y}.mvt")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/vnd.mapbox-vector-tile")
    assert response.content
    assert response.headers["cache-control"] == "private, max-age=300"
    repeated = client.get(
        f"/api/v1/layers/{layer_id}/tiles/10/{x}/{y}.mvt",
        headers={"If-None-Match": response.headers["etag"]},
    )
    assert repeated.status_code == 304
    assert repeated.content == b""
