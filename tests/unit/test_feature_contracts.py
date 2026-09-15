from heatroute.services.feature_contracts import (
    validate_attribute_mapping,
    validate_attributes,
)


def test_mapping_rejects_unknown_and_missing_contract_fields() -> None:
    errors = validate_attribute_mapping(
        "network_edge", {"from_node_id", "to_node_id", "invented_capacity"}
    )

    assert errors == [
        "unknown canonical field: invented_capacity",
        "required canonical field is not mapped: circuit",
        "required canonical field is not mapped: source_network_id",
        "required canonical field is not mapped: status",
    ]


def test_canonical_enums_and_non_negative_measurements_are_validated() -> None:
    issues = validate_attributes(
        "building",
        {"building_role": "guess", "height_m": -1},
    )

    assert issues == [
        ("ATTRIBUTE_ENUM_INVALID", "building_role"),
        ("ATTRIBUTE_NUMBER_INVALID", "height_m"),
    ]


def test_coverage_contract_requires_known_kinds() -> None:
    issues = validate_attributes(
        "coverage_area",
        {
            "covered_kinds": ["building", "imaginary_kind"],
            "completeness": "known_complete",
            "assertion_source": "organizer delivery note",
        },
    )

    assert issues == [("COVERED_KINDS_INVALID", "covered_kinds")]
