from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

import geopandas as gpd
import numpy as np
import pandas as pd
import pytest
from pyogrio.raw import write
from shapely import to_wkb
from shapely.geometry import Point

from heatroute.services.artifacts import LocalArtifactStorage
from heatroute.services.inspection import (
    InspectionRejectedError,
    inspect_vector_artifact,
    report_as_json,
    resolve_artifact_path,
)

EXAMPLE = Path("examples/raw_buildings.demo.geojson")


def test_geojson_inspection_reports_fields_crs_and_bounded_sample(tmp_path: Path) -> None:
    storage = LocalArtifactStorage(tmp_path / "artifacts", max_upload_bytes=1024 * 1024)
    with EXAMPLE.open("rb") as source:
        stored = storage.store(source)
    artifact_path = resolve_artifact_path(
        tmp_path / "artifacts", stored.storage_key, stored.sha256
    )

    report = report_as_json(
        inspect_vector_artifact(
            artifact_path,
            declared_format="geojson",
            temp_root=tmp_path / "gdal-tmp",
        )
    )

    assert report["driver"] == "GeoJSON"
    assert report["adapter_name"] == "pyogrio-geojson"
    assert len(report["layers"]) == 1
    layer = report["layers"][0]
    assert layer["geometry_type"] in {"Polygon", "MultiPolygon"}
    assert layer["crs"] == "EPSG:4326"
    assert {field["name"] for field in layer["fields"]} >= {"BID", "HEIGHT_CM"}
    assert len(layer["sample"]) <= 5
    assert report["publish_blockers"][0]["code"] == "MAPPING_REQUIRED"


def test_inspection_rejects_driver_that_does_not_match_declaration(tmp_path: Path) -> None:
    with pytest.raises(InspectionRejectedError, match="opened as"):
        inspect_vector_artifact(
            EXAMPLE,
            declared_format="gpkg",
            temp_root=tmp_path / "gdal-tmp",
        )


def test_geopackage_inspection_supports_multiple_local_layers(tmp_path: Path) -> None:
    geopackage = tmp_path / "source.gpkg"
    write(
        geopackage,
        np.array([to_wkb(Point(37.61, 55.75))], dtype=object),
        [np.array(["node-1"], dtype=object)],
        ["name"],
        layer="nodes",
        driver="GPKG",
        geometry_type="Point",
        crs="EPSG:4326",
    )
    write(
        geopackage,
        np.array([to_wkb(Point(37.62, 55.76))], dtype=object),
        [np.array(["gate-1"], dtype=object)],
        ["name"],
        layer="gates",
        driver="GPKG",
        geometry_type="Point",
        crs="EPSG:4326",
        append=True,
    )

    report = report_as_json(
        inspect_vector_artifact(
            geopackage,
            declared_format="gpkg",
            temp_root=tmp_path / "gdal-tmp",
        )
    )

    assert report["driver"] == "GPKG"
    assert {layer["name"] for layer in report["layers"]} == {"nodes", "gates"}


def test_artifact_resolution_rejects_non_content_addressed_path(tmp_path: Path) -> None:
    with pytest.raises(InspectionRejectedError, match="does not match"):
        resolve_artifact_path(tmp_path, "../outside.geojson", "0" * 64)


def test_csv_inspection_reads_header_without_guessing_coordinates(tmp_path: Path) -> None:
    source = tmp_path / "nodes.csv"
    source.write_text(
        "node_id;x_coord;y_coord;load_kw\nN-1;413000;6179000;120\n",
        encoding="utf-8",
    )

    report = report_as_json(
        inspect_vector_artifact(
            source,
            declared_format="csv",
            temp_root=tmp_path / "unused",
        )
    )

    assert report["driver"] == "CSV"
    assert report["layers"][0]["delimiter"] == ";"
    assert report["layers"][0]["geometry_type"] is None
    assert {field["name"] for field in report["layers"][0]["fields"]} == {
        "node_id",
        "x_coord",
        "y_coord",
        "load_kw",
    }
    assert "CSV_COORDINATES_UNDECLARED" in {
        warning["code"] for warning in report["warnings"]
    }


def test_zipped_shapefile_is_extracted_and_inspected_safely(tmp_path: Path) -> None:
    source_root = tmp_path / "source"
    source_root.mkdir()
    shapefile = source_root / "nodes.shp"
    write(
        shapefile,
        np.array([to_wkb(Point(37.61, 55.75))], dtype=object),
        [np.array(["node-1"], dtype=object)],
        ["name"],
        driver="ESRI Shapefile",
        geometry_type="Point",
        crs="EPSG:4326",
    )
    archive = tmp_path / "nodes.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as output:
        for sidecar in source_root.iterdir():
            output.write(sidecar, f"nested/{sidecar.name}")

    report = report_as_json(
        inspect_vector_artifact(
            archive,
            declared_format="shapefile_zip",
            temp_root=tmp_path / "extract",
        )
    )

    assert report["driver"] == "ESRI Shapefile"
    assert report["layers"][0]["feature_count"] == 1
    assert report["layers"][0]["crs"] == "EPSG:4326"


def test_zipped_shapefile_rejects_path_traversal(tmp_path: Path) -> None:
    archive = tmp_path / "unsafe.zip"
    with ZipFile(archive, "w", ZIP_DEFLATED) as output:
        output.writestr("../nodes.shp", b"not relevant")
        output.writestr("../nodes.shx", b"not relevant")
        output.writestr("../nodes.dbf", b"not relevant")

    with pytest.raises(InspectionRejectedError, match="unsafe path"):
        inspect_vector_artifact(
            archive,
            declared_format="shapefile_zip",
            temp_root=tmp_path / "extract",
        )


def test_geoparquet_inspection_uses_standard_geo_metadata(tmp_path: Path) -> None:
    source = tmp_path / "utility-lines.parquet"
    frame = gpd.GeoDataFrame(
        pd.DataFrame({"asset_id": ["U-1"]}),
        geometry=[Point(37.61, 55.75)],
        crs="EPSG:4326",
    )
    frame.to_parquet(source)

    report = report_as_json(
        inspect_vector_artifact(
            source,
            declared_format="geoparquet",
            temp_root=tmp_path / "unused",
        )
    )

    assert report["driver"] == "GeoParquet"
    assert report["layers"][0]["name"] == "utility-lines"
    assert report["layers"][0]["feature_count"] == 1
    assert report["layers"][0]["crs"] == "EPSG:4326"
    assert report["layers"][0]["sample"] == [{"asset_id": "U-1"}]
