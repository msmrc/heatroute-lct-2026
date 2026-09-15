from __future__ import annotations

from typing import Any

ATTRIBUTE_FIELDS_BY_KIND: dict[str, frozenset[str]] = {
    "building": frozenset(
        {
            "external_name",
            "address",
            "building_role",
            "height_m",
            "height_source",
            "entry_points",
            "required_load_kw",
            "availability_date",
        }
    ),
    "road": frozenset(
        {
            "road_class",
            "surface_type",
            "width_m",
            "crossing_policy",
            "construction_availability",
        }
    ),
    "utility_line": frozenset(
        {
            "utility_type",
            "horizontal_accuracy_m",
            "elevation_m",
            "depth_m",
            "vertical_datum",
            "protection_profile_key",
        }
    ),
    "network_node": frozenset(
        {
            "node_type",
            "source_network_id",
            "circuit",
            "elevation_m",
            "pressure_pa",
            "temperature_c",
            "is_source",
            "is_consumer",
            "connection_permission",
        }
    ),
    "network_edge": frozenset(
        {
            "from_node_id",
            "to_node_id",
            "source_network_id",
            "circuit",
            "diameter_m",
            "material",
            "roughness_m",
            "installation_method",
            "measured_length_m",
            "geometry_length_m",
            "flow_capacity_kw",
            "status",
        }
    ),
    "connection_candidate": frozenset(
        {
            "network_node_id",
            "approved_edge_location",
            "permission",
            "permission_source",
            "valid_from",
            "valid_to",
            "available_capacity_kw",
            "capacity_basis",
            "reserved_capacity_kw",
            "compatible_circuit_layouts",
            "connection_method",
            "approach_heading_constraints",
            "allowed_connector_length_m",
            "review_notes",
        }
    ),
    "forbidden_zone": frozenset(
        {
            "restriction_kind",
            "reason",
            "source_reference",
            "valid_from",
            "valid_to",
            "severity",
            "review_status",
        }
    ),
    "coverage_area": frozenset(
        {
            "covered_kinds",
            "completeness",
            "valid_from",
            "valid_to",
            "assertion_source",
            "exclusions",
        }
    ),
    "crossing_portal": frozenset(
        {
            "entry_point_id",
            "exit_point_id",
            "footprint_id",
            "applies_to_feature_ids",
            "method",
            "width_m",
            "allowed_headings",
            "length_m",
            "quantity_model_key",
            "approval_status",
            "evidence_notes",
            "vertical_constraints",
        }
    ),
    "entry_gate": frozenset(
        {
            "target_building_id",
            "entry_point",
            "permitted_heading",
            "max_connector_length_m",
            "exception_rule_ids",
        }
    ),
}

REQUIRED_FIELDS_BY_KIND: dict[str, frozenset[str]] = {
    "building": frozenset({"building_role"}),
    "road": frozenset({"crossing_policy"}),
    "utility_line": frozenset({"utility_type"}),
    "network_node": frozenset(
        {"node_type", "source_network_id", "circuit", "connection_permission"}
    ),
    "network_edge": frozenset(
        {"from_node_id", "to_node_id", "source_network_id", "circuit", "status"}
    ),
    "connection_candidate": frozenset({"permission", "capacity_basis"}),
    "forbidden_zone": frozenset({"restriction_kind", "severity"}),
    "coverage_area": frozenset({"covered_kinds", "completeness", "assertion_source"}),
    "crossing_portal": frozenset({"method", "approval_status"}),
    "entry_gate": frozenset({"target_building_id", "entry_point"}),
}

ENUM_VALUES: dict[tuple[str, str], frozenset[str]] = {
    ("building", "building_role"): frozenset({"target", "existing", "planned"}),
    ("road", "crossing_policy"): frozenset(
        {"forbidden", "portal_only", "profile_defined", "unknown"}
    ),
    ("utility_line", "utility_type"): frozenset(
        {"heat", "water", "sewer", "electric", "gas", "telecom", "other", "unknown"}
    ),
    ("network_node", "circuit"): frozenset(
        {"supply", "return", "paired_corridor", "unknown"}
    ),
    ("network_node", "connection_permission"): frozenset(
        {"allowed", "forbidden", "unknown"}
    ),
    ("network_edge", "circuit"): frozenset(
        {"supply", "return", "paired_corridor", "unknown"}
    ),
    ("connection_candidate", "permission"): frozenset(
        {"allowed", "forbidden", "unknown"}
    ),
    ("connection_candidate", "capacity_basis"): frozenset(
        {"net_available", "gross_with_separate_reservations", "unknown"}
    ),
    ("forbidden_zone", "severity"): frozenset({"hard"}),
    ("coverage_area", "completeness"): frozenset(
        {"known_complete", "partial", "unknown", "synthetic"}
    ),
}

NON_NEGATIVE_FIELDS = frozenset(
    {
        "height_m",
        "required_load_kw",
        "width_m",
        "horizontal_accuracy_m",
        "depth_m",
        "pressure_pa",
        "diameter_m",
        "roughness_m",
        "measured_length_m",
        "geometry_length_m",
        "flow_capacity_kw",
        "available_capacity_kw",
        "reserved_capacity_kw",
        "allowed_connector_length_m",
        "length_m",
        "max_connector_length_m",
    }
)

REFERENCE_FIELDS: dict[str, dict[str, str]] = {
    "network_edge": {"from_node_id": "network_node", "to_node_id": "network_node"},
    "connection_candidate": {"network_node_id": "network_node"},
    "entry_gate": {"target_building_id": "building"},
}


def validate_attribute_mapping(target_kind: str, target_fields: set[str]) -> list[str]:
    allowed = ATTRIBUTE_FIELDS_BY_KIND[target_kind]
    errors = [f"unknown canonical field: {field}" for field in sorted(target_fields - allowed)]
    missing = REQUIRED_FIELDS_BY_KIND.get(target_kind, frozenset()) - target_fields
    errors.extend(f"required canonical field is not mapped: {field}" for field in sorted(missing))
    return errors


def validate_attributes(target_kind: str, attributes: dict[str, Any]) -> list[tuple[str, str]]:
    issues: list[tuple[str, str]] = []
    for field in REQUIRED_FIELDS_BY_KIND.get(target_kind, frozenset()):
        value = attributes.get(field)
        if value is None or (isinstance(value, str) and not value.strip()):
            issues.append(("REQUIRED_ATTRIBUTE_MISSING", field))
    for field, value in attributes.items():
        if value is None:
            continue
        allowed_enum = ENUM_VALUES.get((target_kind, field))
        if allowed_enum is not None and str(value) not in allowed_enum:
            issues.append(("ATTRIBUTE_ENUM_INVALID", field))
        if field in NON_NEGATIVE_FIELDS and (
            isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0
        ):
            issues.append(("ATTRIBUTE_NUMBER_INVALID", field))
    if target_kind == "coverage_area":
        covered_kinds = attributes.get("covered_kinds")
        if (
            not isinstance(covered_kinds, list)
            or not covered_kinds
            or any(kind not in ATTRIBUTE_FIELDS_BY_KIND for kind in covered_kinds)
        ):
            issues.append(("COVERED_KINDS_INVALID", "covered_kinds"))
    return issues
