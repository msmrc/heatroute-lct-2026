import pytest
from pydantic import ValidationError

from heatroute.api.schemas import MappingPutRequest, SafeTransform
from heatroute.models import ImportReport, Project
from heatroute.services.mappings import MappingValidationError, validate_mapping_definition


def test_approved_unit_conversion_uses_exact_factor() -> None:
    transform = SafeTransform(
        op="unit_convert",
        from_unit="cm",
        to_unit="m",
        factor="0.01",
    )
    assert str(transform.factor) == "0.01"


@pytest.mark.parametrize("factor", ["0.1", "0.0100001", "1"])
def test_unit_conversion_rejects_unapproved_factor(factor: str) -> None:
    with pytest.raises(ValidationError, match="approved whitelist"):
        SafeTransform(
            op="unit_convert",
            from_unit="cm",
            to_unit="m",
            factor=factor,
        )


def test_transform_language_rejects_executable_operations() -> None:
    with pytest.raises(ValidationError):
        SafeTransform.model_validate({"op": "eval", "value": "__import__('os')"})


def test_csv_mapping_requires_distinct_explicit_coordinate_columns() -> None:
    with pytest.raises(ValidationError, match="different source columns"):
        MappingPutRequest.model_validate(
            {
                "profile_name": "Nodes",
                "layer_name": "csv",
                "target_kind": "network_node",
                "source_id_field": "node_id",
                "source_crs": "EPSG:32637",
                "coordinate_columns": {"x": "coordinate", "y": "coordinate"},
                "fields": {"node_type": {"source_field": "type"}},
                "missing_policy": "quarantine",
            }
        )


def test_mapping_target_fields_must_be_safe_data_keys() -> None:
    with pytest.raises(ValidationError, match="lower_snake_case"):
        MappingPutRequest.model_validate(
            {
                "profile_name": "Nodes",
                "layer_name": "csv",
                "target_kind": "network_node",
                "source_id_field": "node_id",
                "source_crs": "EPSG:32637",
                "coordinate_columns": {"x": "x", "y": "y"},
                "fields": {"name; DROP TABLE": {"source_field": "name"}},
                "missing_policy": "reject",
            }
        )


def test_csv_mapping_requires_existing_explicit_xy_fields() -> None:
    report = ImportReport(
        stage="inspection",
        layers=[
            {
                "name": "csv",
                "fields": [
                    {"name": "node_id", "dtype": "string"},
                    {"name": "x", "dtype": "string"},
                    {"name": "type", "dtype": "string"},
                ],
            }
        ],
    )
    project = Project(working_crs="EPSG:32637", crs_confirmed=True)
    definition = MappingPutRequest.model_validate(
        {
            "profile_name": "Nodes",
            "layer_name": "csv",
            "target_kind": "network_node",
            "source_id_field": "node_id",
            "source_crs": "EPSG:32637",
            "coordinate_columns": {"x": "x", "y": "missing_y"},
            "fields": {"node_type": {"source_field": "type"}},
            "missing_policy": "quarantine",
        }
    ).model_dump(mode="json", exclude={"profile_id", "profile_name"})

    with pytest.raises(MappingValidationError, match="missing_y"):
        validate_mapping_definition(
            definition=definition,
            report=report,
            declared_format="csv",
            project=project,
        )


def test_mapping_rejects_crs_that_conflicts_with_inspection() -> None:
    report = ImportReport(
        stage="inspection",
        layers=[
            {
                "name": "buildings",
                "crs": "EPSG:4326",
                "fields": [
                    {"name": "id", "dtype": "object"},
                    {"name": "name", "dtype": "object"},
                ],
            }
        ],
    )
    project = Project(working_crs="EPSG:32637", crs_confirmed=True)
    definition = MappingPutRequest.model_validate(
        {
            "profile_name": "Buildings",
            "layer_name": "buildings",
            "target_kind": "building",
            "source_id_field": "id",
            "source_crs": "EPSG:3857",
            "fields": {
                "external_name": {"source_field": "name"},
                "building_role": {
                    "transforms": [{"op": "constant", "value": "existing"}]
                },
            },
            "missing_policy": "report_and_keep_null",
        }
    ).model_dump(mode="json", exclude={"profile_id", "profile_name"})

    with pytest.raises(MappingValidationError, match="conflicts"):
        validate_mapping_definition(
            definition=definition,
            report=report,
            declared_format="geojson",
            project=project,
        )


def test_mapping_requires_confirmation_when_source_does_not_declare_crs() -> None:
    report = ImportReport(
        stage="inspection",
        layers=[
            {
                "name": "csv",
                "crs": None,
                "fields": [
                    {"name": "id", "dtype": "string"},
                    {"name": "x", "dtype": "string"},
                    {"name": "y", "dtype": "string"},
                    {"name": "type", "dtype": "string"},
                ],
            }
        ],
    )
    project = Project(working_crs="EPSG:32637", crs_confirmed=True)
    definition = MappingPutRequest.model_validate(
        {
            "profile_name": "Nodes",
            "layer_name": "csv",
            "target_kind": "network_node",
            "source_id_field": "id",
            "source_crs": "EPSG:32637",
            "coordinate_columns": {"x": "x", "y": "y"},
            "fields": {
                "node_type": {"source_field": "type"},
                "source_network_id": {
                    "transforms": [{"op": "constant", "value": "NET-1"}]
                },
                "circuit": {"transforms": [{"op": "constant", "value": "unknown"}]},
                "connection_permission": {
                    "transforms": [{"op": "constant", "value": "unknown"}]
                },
            },
            "missing_policy": "quarantine",
        }
    ).model_dump(mode="json", exclude={"profile_id", "profile_name"})

    with pytest.raises(MappingValidationError, match="explicitly confirmed"):
        validate_mapping_definition(
            definition=definition,
            report=report,
            declared_format="csv",
            project=project,
        )

    definition["source_crs_confirmed"] = True
    normalized = validate_mapping_definition(
        definition=definition,
        report=report,
        declared_format="csv",
        project=project,
    )
    assert normalized["source_crs"] == "EPSG:32637"
