from uuid import uuid4

import pytest
from pyproj import Transformer

from heatroute.services.model_context import CanonicalRecord, materialize_records


def record(
    kind: str,
    source_id: str,
    geometry: dict[str, object],
    attributes: dict[str, object],
) -> CanonicalRecord:
    return CanonicalRecord(
        id=uuid4(),
        dataset_version_id=uuid4(),
        source_id=source_id,
        kind=kind,
        lifecycle_status="existing",
        attributes=attributes,
        geometry_wgs84=geometry,
    )


def test_materializes_selected_canonical_records_in_metric_crs() -> None:
    records = (
        record(
            "network_node",
            "N1",
            {"type": "Point", "coordinates": [37.62, 55.75]},
            {"circuit": "supply", "source_network_id": "heat-1"},
        ),
        record(
            "network_node",
            "N2",
            {"type": "Point", "coordinates": [37.621, 55.75]},
            {"circuit": "supply", "source_network_id": "heat-1"},
        ),
        record(
            "network_edge",
            "E1",
            {
                "type": "LineString",
                "coordinates": [[37.62, 55.75], [37.621, 55.75]],
            },
            {
                "from_node_id": "N1",
                "to_node_id": "N2",
                "circuit": "supply",
                "source_network_id": "heat-1",
            },
        ),
        record(
            "building",
            "B1",
            {
                "type": "Polygon",
                "coordinates": [
                    [
                        [37.6202, 55.7499],
                        [37.6203, 55.7499],
                        [37.6203, 55.7501],
                        [37.6202, 55.7501],
                        [37.6202, 55.7499],
                    ]
                ],
            },
            {"building_role": "existing"},
        ),
        record(
            "connection_candidate",
            "C1",
            {"type": "Point", "coordinates": [37.62, 55.75]},
            {
                "network_node_id": "N1",
                "permission": "allowed",
                "capacity_basis": "net_available",
                "available_capacity_kw": 500,
            },
        ),
    )

    model = materialize_records(records, working_crs="EPSG:32637")

    assert not model.findings
    assert model.topology.valid
    assert model.network.incident_edges("N1")[0].source_id == "E1"
    assert model.network.edges["E1"].geometry.length > 50
    assert {feature.kind for feature in model.features} == {
        "building",
        "network_edge",
        "network_node",
    }
    assert model.candidates[0].network_node_id == "N1"


def test_materialization_reports_invalid_geometry_without_partial_coercion() -> None:
    invalid = record(
        "network_node",
        "N1",
        {
            "type": "LineString",
            "coordinates": [[37.62, 55.75], [37.621, 55.75]],
        },
        {"circuit": "supply", "source_network_id": "heat-1"},
    )

    model = materialize_records((invalid,), working_crs="EPSG:32637")

    assert not model.network.nodes
    assert model.findings[0].code == "CANONICAL_FEATURE_MATERIALIZATION_FAILED"


def test_working_crs_change_reprojects_coordinates_instead_of_relabeling_them() -> None:
    source = record(
        "network_node",
        "N1",
        {"type": "Point", "coordinates": [37.62, 55.75]},
        {"circuit": "supply", "source_network_id": "heat-1"},
    )
    utm37 = materialize_records((source,), working_crs="EPSG:32637")
    web_mercator = materialize_records((source,), working_crs="EPSG:3857")
    expected_utm = Transformer.from_crs(
        "EPSG:4326", "EPSG:32637", always_xy=True
    ).transform(37.62, 55.75)

    assert utm37.network.nodes["N1"].geometry.coords[0] == pytest.approx(expected_utm)
    assert web_mercator.network.nodes["N1"].geometry.x != pytest.approx(expected_utm[0])
    assert utm37.network.nodes["N1"].geometry.x != pytest.approx(37.62)
