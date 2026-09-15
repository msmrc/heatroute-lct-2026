from decimal import Decimal

import pytest
import shapely

from heatroute.services import validation
from heatroute.services.validation import (
    TransformValueError,
    _apply_transforms,
    _transformers,
    _validated_geometry,
)


@pytest.mark.parametrize(
    ("value", "transforms", "expected"),
    [
        ("  name  ", [{"op": "trim"}], "name"),
        ("12,50", [{"op": "parse_decimal", "decimal_separator": ","}], 12.5),
        ("EXIST", [{"op": "enum_map", "mapping": {"EXIST": "existing"}}], "existing"),
        (
            "1200",
            [
                {"op": "parse_decimal"},
                {"op": "unit_convert", "factor": Decimal("0.01")},
            ],
            12.0,
        ),
        ("2026-09-08", [{"op": "date_parse", "format": "%Y-%m-%d"}], "2026-09-08"),
        (None, [{"op": "constant", "value": "unknown"}], "unknown"),
        ("unchanged", [{"op": "rename", "to": "ignored_here"}], "unchanged"),
    ],
)
def test_whitelisted_transforms_are_deterministic(
    value: object, transforms: list[dict[str, object]], expected: object
) -> None:
    assert _apply_transforms(value, transforms) == expected


@pytest.mark.parametrize(
    ("value", "transforms"),
    [
        ("not-a-number", [{"op": "parse_decimal"}]),
        ("missing", [{"op": "enum_map", "mapping": {"known": "value"}}]),
        (12, [{"op": "trim"}]),
        ("2026/09/08", [{"op": "date_parse", "format": "%Y-%m-%d"}]),
    ],
)
def test_whitelisted_transforms_reject_invalid_values(
    value: object, transforms: list[dict[str, object]]
) -> None:
    with pytest.raises(TransformValueError):
        _apply_transforms(value, transforms)


def test_geometry_validation_transforms_valid_polygon_to_wgs84() -> None:
    source_crs, to_wgs84, to_working = _transformers("EPSG:4326", "EPSG:32637")
    polygon = shapely.Polygon(
        [
            (37.62, 55.75),
            (37.621, 55.75),
            (37.621, 55.751),
            (37.62, 55.751),
            (37.62, 55.75),
        ]
    )

    geometry, issue = _validated_geometry(
        shapely.to_wkb(polygon),
        csv_point=None,
        source_crs=source_crs,
        to_wgs84=to_wgs84,
        to_working=to_working,
        target_kind="building",
    )

    assert issue is None
    assert geometry is not None
    assert geometry.geom_type == "Polygon"
    assert shapely.equals_exact(geometry, polygon, tolerance=1e-10)


def test_geometry_validation_preserves_source_z_ordinates() -> None:
    source_crs, to_wgs84, to_working = _transformers("EPSG:4326", "EPSG:32637")
    line = shapely.LineString([(37.62, 55.75, 148.2), (37.621, 55.751, 147.7)])

    geometry, issue = _validated_geometry(
        shapely.to_wkb(line, output_dimension=3),
        csv_point=None,
        source_crs=source_crs,
        to_wgs84=to_wgs84,
        to_working=to_working,
        target_kind="utility_line",
    )

    assert issue is None
    assert geometry is not None
    assert shapely.has_z(geometry)
    assert [coordinate[2] for coordinate in geometry.coords] == [148.2, 147.7]


@pytest.mark.parametrize(
    ("geometry", "expected_issue"),
    [
        (shapely.Point(37.62, 55.75), "GEOMETRY_TYPE_MISMATCH"),
        (shapely.Point(200, 95), "SOURCE_COORDINATE_OUT_OF_RANGE"),
        (
            shapely.Polygon([(0, 0), (1, 1), (1, 0), (0, 1), (0, 0)]),
            "GEOMETRY_INVALID_SELF-INTERSECTION",
        ),
    ],
)
def test_geometry_validation_quarantines_invalid_geometry(
    geometry: object, expected_issue: str
) -> None:
    source_crs, to_wgs84, to_working = _transformers("EPSG:4326", "EPSG:32637")

    _result, issue = _validated_geometry(
        shapely.to_wkb(geometry),
        csv_point=None,
        source_crs=source_crs,
        to_wgs84=to_wgs84,
        to_working=to_working,
        target_kind="building",
    )

    assert issue == expected_issue


def test_swapped_lon_lat_is_reported_against_working_crs_area() -> None:
    source_crs, to_wgs84, to_working = _transformers("EPSG:4326", "EPSG:32637")
    swapped = shapely.Polygon([(55.75, 37.62), (55.751, 37.62), (55.751, 37.621), (55.75, 37.62)])

    _result, issue = _validated_geometry(
        shapely.to_wkb(swapped),
        csv_point=None,
        source_crs=source_crs,
        to_wgs84=to_wgs84,
        to_working=to_working,
        target_kind="building",
    )

    assert issue == "GEOGRAPHIC_EXTENT_OUTSIDE_WORKING_CRS_AREA"


def test_unavailable_required_crs_operation_never_falls_back_to_ballpark(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def unavailable(*_args: object, **kwargs: object) -> object:
        assert kwargs["allow_ballpark"] is False
        assert kwargs["only_best"] is True
        raise RuntimeError("required grid is unavailable")

    monkeypatch.setattr(validation.Transformer, "from_crs", unavailable)

    with pytest.raises(validation.ImportValidationError, match="unavailable"):
        validation._transformers("EPSG:4326", "EPSG:32637")
