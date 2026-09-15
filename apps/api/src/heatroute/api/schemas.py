import re
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from shapely.geometry import MultiPolygon, Polygon, shape


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DependencyStatus(StrictModel):
    status: Literal["ok", "error"]
    detail: str | None = None


class ReadinessResponse(StrictModel):
    status: Literal["ready", "not_ready"]
    checks: dict[str, DependencyStatus]


class CapabilitiesResponse(StrictModel):
    schema_version: str
    source: Literal["runtime"] = "runtime"
    imports: list[str]
    solvers: list[str]
    exports: list[str]
    demo_seed: bool
    engineering: dict[str, Literal["not_performed", "available"]]
    construction_methods: list[str]


class AuditEventResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    actor_user_id: UUID | None
    request_id: str
    method: str
    path: str
    status_code: int
    action: str
    metadata: dict[str, Any]
    created_at: datetime


class SearchBudget(StrictModel):
    max_expanded_states: int = Field(default=100_000, ge=1, le=1_000_000)
    max_wall_time_s: float = Field(default=120, gt=0, le=3_600)
    max_memory_mb: float = Field(default=4_096, gt=0, le=65_536)


class DemoSearchSettings(StrictModel):
    resolution_m: float = Field(default=20, ge=2, le=100)
    search_buffer_m: float = Field(default=400, ge=50, le=2_000)
    budget: SearchBudget = Field(default_factory=SearchBudget)
    max_alternatives: int = Field(default=1, ge=1, le=5)
    refinement_resolution_m: float | None = Field(default=None, ge=1, le=100)


def _default_objectives() -> list[Literal["shortest", "estimated_cost", "least_unverified"]]:
    return ["shortest"]


class PointToPointInput(StrictModel):
    input_mode: Literal["point_to_point_demo"] = "point_to_point_demo"
    entry_point_wgs84: tuple[float, float]
    goal_point_wgs84: tuple[float, float]
    forbidden_rectangles_wgs84: list[tuple[float, float, float, float]] = Field(
        default_factory=list, max_length=100
    )
    corridor_width_m: float = Field(default=8, gt=0, le=50)
    search_settings: DemoSearchSettings = Field(default_factory=DemoSearchSettings)
    explicit_assumptions: list[str] = Field(default_factory=list, max_length=20)

    @field_validator("entry_point_wgs84", "goal_point_wgs84")
    @classmethod
    def valid_coordinate(cls, value: tuple[float, float]) -> tuple[float, float]:
        lon, lat = value
        if not -180 <= lon <= 180 or not -90 <= lat <= 90:
            raise ValueError("coordinate must be valid WGS84 longitude/latitude")
        return value

    @model_validator(mode="after")
    def valid_rectangles(self) -> "PointToPointInput":
        for west, south, east, north in self.forbidden_rectangles_wgs84:
            if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
                raise ValueError("forbidden rectangle must be a valid WGS84 bbox")
        return self


class DemoRunRequest(PointToPointInput):
    algorithm: Literal["astar", "dijkstra"] = "astar"


class ProjectCreate(StrictModel):
    name: str = Field(min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=2_000)
    working_crs: str | None = Field(default=None, min_length=1, max_length=2_000)
    crs_confirmed: bool = False
    source_mode: Literal["synthetic", "mixed", "provided"] = "synthetic"


class ProjectUpdate(StrictModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    description: str | None = Field(default=None, max_length=2_000)
    working_crs: str | None = Field(default=None, min_length=1, max_length=2_000)
    crs_confirmed: bool | None = None

    @model_validator(mode="after")
    def at_least_one_field(self) -> "ProjectUpdate":
        if not self.model_fields_set:
            raise ValueError("at least one project field must be supplied")
        return self


class ProjectResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    name: str
    description: str | None
    working_crs: str | None
    crs_confirmed: bool
    source_mode: str
    current_revision: int
    created_at: datetime
    updated_at: datetime


class LoginRequest(StrictModel):
    workspace_id: UUID | None = None
    email: str = Field(min_length=3, max_length=320)
    password: str = Field(min_length=12, max_length=256)


class UserCreateRequest(StrictModel):
    email: str = Field(min_length=3, max_length=320)
    password: str = Field(min_length=12, max_length=256)
    role: Literal["viewer", "editor", "admin"]


class UserResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    email: str
    role: str
    is_active: bool


class AuthSessionResponse(StrictModel):
    user: UserResponse
    csrf_token: str
    expires_at: datetime


class RawArtifactResponse(StrictModel):
    id: UUID
    sha256: str
    size_bytes: int
    media_type: str | None
    original_filename: str
    deduplicated: bool


class DatasetVersionResponse(StrictModel):
    id: UUID
    dataset_id: UUID
    version: int
    status: str
    source_type: str
    source_name: str
    source_observed_at: datetime | None
    raw_hashes: list[str]
    working_crs: str | None
    license_note: str | None
    contains_sensitive_infrastructure: bool
    published_at: datetime | None = None
    publication_policy: dict[str, Any] | None = None
    created_at: datetime


class DatasetUploadAcceptedResponse(StrictModel):
    import_id: UUID
    job_id: UUID
    job_state: str
    dataset_id: UUID
    dataset_version: DatasetVersionResponse
    artifact: RawArtifactResponse
    status_url: str


class DatasetImportResponse(StrictModel):
    id: UUID
    project_id: UUID
    dataset_version_id: UUID
    job_id: UUID
    state: str
    job_state: str
    phase: str | None
    result: dict[str, Any] | None
    created_at: datetime


class JobResponse(StrictModel):
    id: UUID
    kind: str
    state: str
    phase: str | None
    attempt: int
    progress_current: int | None
    progress_total: int | None
    progress_unit: str | None
    retryable: bool
    error_code: str | None
    project_id: UUID | None
    resource_type: Literal["run", "import"] | None
    resource_id: UUID | None
    created_at: datetime
    updated_at: datetime


class InspectionFieldResponse(StrictModel):
    name: str
    dtype: str


class InspectionLayerResponse(StrictModel):
    name: str
    geometry_type: str | None
    crs: str | None
    encoding: str | None
    delimiter: str | None = None
    feature_count: int
    extent: list[float] | None
    fields: list[InspectionFieldResponse]
    sample: list[dict[str, Any]]


class ImportReportResponse(StrictModel):
    id: UUID
    import_id: UUID
    stage: str
    import_state: str
    counts: dict[str, int]
    layers: list[InspectionLayerResponse]
    errors: list[dict[str, Any]]
    warnings: list[dict[str, Any]]
    publish_blockers: list[dict[str, Any]]
    missing_value_distribution: dict[str, int]
    source_references: list[dict[str, Any]]
    repairs: list[dict[str, Any]]
    crs_diagnostics: list[dict[str, Any]]
    topology_diagnostics: list[dict[str, Any]]
    coverage_diagnostics: list[dict[str, Any]]
    created_at: datetime
    updated_at: datetime


class SafeTransform(StrictModel):
    op: Literal[
        "rename",
        "trim",
        "parse_decimal",
        "enum_map",
        "unit_convert",
        "date_parse",
        "constant",
    ]
    to: str | None = Field(default=None, min_length=1, max_length=120)
    decimal_separator: Literal[".", ","] | None = None
    mapping: dict[str, str] | None = None
    unmapped: str | None = Field(default=None, max_length=500)
    from_unit: str | None = Field(default=None, min_length=1, max_length=32)
    to_unit: str | None = Field(default=None, min_length=1, max_length=32)
    factor: Decimal | None = None
    format: str | None = Field(default=None, min_length=1, max_length=80)
    value: str | int | float | bool | None = None

    @model_validator(mode="after")
    def valid_operation_shape(self) -> "SafeTransform":
        allowed_by_operation = {
            "rename": {"op", "to"},
            "trim": {"op"},
            "parse_decimal": {"op", "decimal_separator"},
            "enum_map": {"op", "mapping", "unmapped"},
            "unit_convert": {"op", "from_unit", "to_unit", "factor"},
            "date_parse": {"op", "format"},
            "constant": {"op", "value"},
        }
        unexpected = self.model_fields_set - allowed_by_operation[self.op]
        if unexpected:
            raise ValueError(f"fields are not valid for {self.op}: {sorted(unexpected)}")
        required_by_operation = {
            "rename": {"to"},
            "enum_map": {"mapping"},
            "unit_convert": {"from_unit", "to_unit", "factor"},
            "date_parse": {"format"},
            "constant": {"value"},
        }
        missing = required_by_operation.get(self.op, set()) - self.model_fields_set
        if missing:
            raise ValueError(f"fields are required for {self.op}: {sorted(missing)}")
        if self.op in {"rename", "enum_map", "unit_convert", "date_parse"}:
            null_required = {
                field for field in required_by_operation[self.op] if getattr(self, field) is None
            }
            if null_required:
                raise ValueError(f"fields must not be null for {self.op}: {sorted(null_required)}")
        if self.mapping is not None:
            if not self.mapping or len(self.mapping) > 1_000:
                raise ValueError("enum mapping must contain 1..1000 entries")
            if any(len(key) > 500 or len(value) > 500 for key, value in self.mapping.items()):
                raise ValueError("enum mapping keys and values are limited to 500 characters")
        if self.op == "unit_convert":
            known_factors = {
                ("mm", "m"): Decimal("0.001"),
                ("cm", "m"): Decimal("0.01"),
                ("km", "m"): Decimal("1000"),
                ("w", "kw"): Decimal("0.001"),
                ("mw", "kw"): Decimal("1000"),
            }
            expected = known_factors.get((str(self.from_unit).lower(), str(self.to_unit).lower()))
            if expected is None or self.factor != expected:
                raise ValueError("unit conversion and factor are not in the approved whitelist")
        return self


class FieldMapping(StrictModel):
    source_field: str | None = Field(default=None, min_length=1, max_length=120)
    transforms: list[SafeTransform] = Field(default_factory=list, max_length=10)

    @model_validator(mode="after")
    def source_or_constant(self) -> "FieldMapping":
        has_constant = any(transform.op == "constant" for transform in self.transforms)
        if self.source_field is None and not has_constant:
            raise ValueError("source_field is required unless a constant transform is used")
        return self


class CoordinateColumns(StrictModel):
    x: str = Field(min_length=1, max_length=120)
    y: str = Field(min_length=1, max_length=120)

    @model_validator(mode="after")
    def distinct_columns(self) -> "CoordinateColumns":
        if self.x == self.y:
            raise ValueError("X and Y must use different source columns")
        return self


class MappingPutRequest(StrictModel):
    profile_id: UUID | None = None
    profile_name: str = Field(min_length=1, max_length=120)
    layer_name: str = Field(min_length=1, max_length=255)
    source_namespace: str | None = Field(default=None, min_length=1, max_length=255)
    target_kind: Literal[
        "building",
        "road",
        "utility_line",
        "network_node",
        "network_edge",
        "connection_candidate",
        "forbidden_zone",
        "coverage_area",
        "crossing_portal",
        "entry_gate",
    ]
    source_id_field: str = Field(min_length=1, max_length=120)
    source_crs: str = Field(min_length=1, max_length=2_000)
    source_crs_confirmed: bool = False
    coordinate_columns: CoordinateColumns | None = None
    fields: dict[str, FieldMapping] = Field(min_length=1, max_length=128)
    missing_policy: Literal["reject", "quarantine", "report_and_keep_null"]
    notes: str | None = Field(default=None, max_length=2_000)

    @field_validator(
        "profile_name", "layer_name", "source_namespace", "source_id_field", "source_crs"
    )
    @classmethod
    def non_blank_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("value must not be blank")
        return normalized

    @field_validator("fields")
    @classmethod
    def valid_target_field_names(cls, value: dict[str, FieldMapping]) -> dict[str, FieldMapping]:
        pattern = re.compile(r"[a-z][a-z0-9_]{0,63}\Z")
        if any(pattern.fullmatch(name) is None for name in value):
            raise ValueError("target fields must use lower_snake_case identifiers")
        return value


class MappingProfileResponse(StrictModel):
    profile_id: UUID
    revision_id: UUID
    revision: int
    definition_hash: str
    dataset_version_id: UUID
    dataset_version_status: str
    import_state: str


class DatasetPublishRequest(StrictModel):
    confirm_quarantine: bool = False
    coverage_limitations: str | None = Field(default=None, max_length=2_000)


class CanonicalFeatureResponse(StrictModel):
    id: UUID
    logical_id: UUID
    dataset_version_id: UUID
    kind: str
    source_id: str
    source_layer: str
    source_type: str
    lifecycle_status: str
    quality_flags: list[str]
    raw_properties: dict[str, Any]
    attributes: dict[str, Any]
    geometry: dict[str, Any]
    created_at: datetime


class DatasetLayerResponse(StrictModel):
    id: UUID
    dataset_version_id: UUID
    name: str
    ordinal: int
    geometry_type: str | None
    source_crs: str | None
    mapped_kind: str | None
    feature_count: int
    status: str


class DatasetVersionDiffResponse(StrictModel):
    dataset_version_id: UUID
    previous_dataset_version_id: UUID | None
    added: list[str]
    changed: list[str]
    deleted: list[str]
    unchanged: int
    unmapped_layers: list[str]


class ProjectQualityResponse(StrictModel):
    project_id: UUID
    coverage_state: Literal["known_complete", "partial", "unknown", "synthetic"]
    findings: list[dict[str, Any]]


class RuleProfileCreate(StrictModel):
    name: str = Field(min_length=1, max_length=120)
    definition: dict[str, Any]


class RuleProfileVersionCreate(StrictModel):
    definition: dict[str, Any]


class RuleProfileVersionResponse(StrictModel):
    id: UUID
    profile_id: UUID
    revision: int
    definition_hash: str
    status: str
    definition: dict[str, Any]
    created_at: datetime


class RuleProfileResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    project_id: UUID
    name: str
    current_revision: int
    created_at: datetime
    updated_at: datetime
    versions: list[RuleProfileVersionResponse]


class CostCatalogCreate(StrictModel):
    name: str = Field(min_length=1, max_length=120)
    definition: dict[str, Any]


class CostCatalogVersionCreate(StrictModel):
    definition: dict[str, Any]


class CostCatalogVersionResponse(StrictModel):
    id: UUID
    catalog_id: UUID
    revision: int
    definition_hash: str
    status: str
    definition: dict[str, Any]
    created_at: datetime


class CostCatalogResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    project_id: UUID
    name: str
    current_revision: int
    created_at: datetime
    updated_at: datetime
    versions: list[CostCatalogVersionResponse]


class ScenarioCreate(StrictModel):
    name: str = Field(min_length=1, max_length=120)


class ScenarioVerticalProfilePoint(StrictModel):
    chainage_m: float = Field(ge=0, le=10_000_000)
    elevation_m: float = Field(ge=-500, le=10_000)


class ScenarioVerticalCrossing(StrictModel):
    crossing_id: str = Field(min_length=1, max_length=200)
    chainage_m: float = Field(ge=0, le=10_000_000)
    vertical_datum: str | None = Field(default=None, max_length=200)
    elevation_m: float | None = Field(default=None, ge=-500, le=10_000)
    surface_elevation_m: float | None = Field(default=None, ge=-500, le=10_000)
    depth_m: float | None = Field(default=None, ge=0, le=1_000)
    outside_diameter_m: float | None = Field(default=None, gt=0, le=20)


class ScenarioRevisionCreate(StrictModel):
    input_mode: Literal["point_to_point_demo", "building_to_network"] = "point_to_point_demo"
    entry_point_wgs84: tuple[float, float]
    goal_point_wgs84: tuple[float, float] | None = None
    target_building_id: str | None = Field(default=None, min_length=1, max_length=200)
    entry_gate_id: str | None = Field(default=None, min_length=1, max_length=200)
    forbidden_rectangles_wgs84: list[tuple[float, float, float, float]] = Field(
        default_factory=list, max_length=100
    )
    user_forbidden_zones: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    waypoints_wgs84: list[tuple[float, float]] = Field(default_factory=list, max_length=10)
    corridor_width_m: float = Field(default=8, gt=0, le=50)
    circuit_layout: Literal["single", "paired"] = "paired"
    construction_methods: list[str] = Field(default_factory=list, max_length=32)
    construction_method_inputs: dict[str, dict[str, Any]] = Field(
        default_factory=dict, max_length=32
    )
    preferred_corridors: list[dict[str, Any]] = Field(default_factory=list, max_length=32)
    objective_profiles: list[Literal["shortest", "estimated_cost", "least_unverified"]] = Field(
        default_factory=_default_objectives, min_length=1, max_length=3
    )
    search_settings: DemoSearchSettings = Field(default_factory=DemoSearchSettings)
    explicit_assumptions: list[str] = Field(default_factory=list, max_length=20)
    rule_profile_version_id: UUID | None = None
    cost_catalog_version_id: UUID | None = None
    validation_mode: Literal["strict", "exploratory"] = "strict"
    selected_dataset_version_ids: list[UUID] = Field(default_factory=list, max_length=64)
    planning_date: date | None = None
    connection_candidate_ids: list[str] = Field(default_factory=list, max_length=64)
    requested_load_kw: float | None = Field(default=None, ge=0)
    candidate_limit: int = Field(default=5, ge=1, le=50)
    vertical_profile: list[ScenarioVerticalProfilePoint] = Field(
        default_factory=list, max_length=10_000
    )
    vertical_crossings: list[ScenarioVerticalCrossing] = Field(
        default_factory=list, max_length=10_000
    )
    vertical_datum: str | None = Field(default=None, max_length=200)
    route_outside_diameter_m: float | None = Field(default=None, gt=0, le=20)
    minimum_vertical_clearance_m: float | None = Field(default=None, ge=0, le=100)
    maximum_grade_percent: float | None = Field(default=None, gt=0, le=100)

    @field_validator("entry_point_wgs84", "goal_point_wgs84")
    @classmethod
    def valid_coordinate(cls, value: tuple[float, float] | None) -> tuple[float, float] | None:
        if value is None:
            return None
        lon, lat = value
        if not -180 <= lon <= 180 or not -90 <= lat <= 90:
            raise ValueError("coordinate must be valid WGS84 longitude/latitude")
        return value

    @field_validator("waypoints_wgs84")
    @classmethod
    def valid_waypoints(cls, value: list[tuple[float, float]]) -> list[tuple[float, float]]:
        if any(not -180 <= lon <= 180 or not -90 <= lat <= 90 for lon, lat in value):
            raise ValueError("waypoints must be valid WGS84 coordinates")
        return value

    @field_validator("user_forbidden_zones")
    @classmethod
    def valid_user_forbidden_zones(cls, value: list[dict[str, Any]]) -> list[dict[str, Any]]:
        for raw_geometry in value:
            try:
                geometry = shape(raw_geometry)
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError("user forbidden zone must be valid GeoJSON") from error
            if not isinstance(geometry, Polygon | MultiPolygon) or not geometry.is_valid:
                raise ValueError("user forbidden zone must be a valid Polygon or MultiPolygon")
            west, south, east, north = geometry.bounds
            if not (-180 <= west <= east <= 180 and -90 <= south <= north <= 90):
                raise ValueError("user forbidden zone coordinates must be WGS84")
        return value

    @field_validator("construction_methods")
    @classmethod
    def valid_construction_methods(cls, value: list[str]) -> list[str]:
        from heatroute.domain.construction import CONSTRUCTION_METHODS

        normalized = [item.strip() for item in value]
        if any(not item for item in normalized):
            raise ValueError("construction methods must not be blank")
        if len(set(normalized)) != len(normalized):
            raise ValueError("construction methods must be unique")
        unsupported = sorted(set(normalized) - CONSTRUCTION_METHODS.keys())
        if unsupported:
            raise ValueError("unsupported construction methods: " + ", ".join(unsupported))
        return normalized

    @field_validator("construction_method_inputs")
    @classmethod
    def valid_construction_method_inputs(
        cls, value: dict[str, dict[str, Any]]
    ) -> dict[str, dict[str, Any]]:
        from heatroute.domain.construction import CONSTRUCTION_METHODS

        unsupported = sorted(set(value) - CONSTRUCTION_METHODS.keys())
        if unsupported:
            raise ValueError("unsupported construction method inputs: " + ", ".join(unsupported))
        return value

    @field_validator("objective_profiles")
    @classmethod
    def unique_objectives(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("objective profiles must be unique")
        return value

    @field_validator("preferred_corridors")
    @classmethod
    def valid_preferred_corridors(cls, value: list[dict[str, Any]]) -> list[dict[str, Any]]:
        for item in value:
            raw_geometry = item.get("geometry")
            multiplier = item.get("cost_multiplier", 1)
            try:
                geometry = shape(raw_geometry)
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError("preferred corridor must contain valid GeoJSON") from error
            if not isinstance(geometry, Polygon | MultiPolygon) or not geometry.is_valid:
                raise ValueError("preferred corridor geometry must be a valid polygon")
            if (
                isinstance(multiplier, bool)
                or not isinstance(multiplier, int | float)
                or multiplier < 0
            ):
                raise ValueError("preferred corridor cost_multiplier must be non-negative")
        return value

    @model_validator(mode="after")
    def valid_input_mode_contract(self) -> "ScenarioRevisionCreate":
        for west, south, east, north in self.forbidden_rectangles_wgs84:
            if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
                raise ValueError("forbidden rectangle must be a valid WGS84 bbox")
        if self.input_mode == "point_to_point_demo":
            if self.goal_point_wgs84 is None:
                raise ValueError("goal_point_wgs84 is required for point_to_point_demo")
        else:
            missing = [
                name
                for name, value in (
                    ("target_building_id", self.target_building_id),
                    ("entry_gate_id", self.entry_gate_id),
                    ("rule_profile_version_id", self.rule_profile_version_id),
                    ("planning_date", self.planning_date),
                )
                if value is None
            ]
            if not self.selected_dataset_version_ids:
                missing.append("selected_dataset_version_ids")
            if not self.connection_candidate_ids:
                missing.append("connection_candidate_ids")
            if not self.construction_methods:
                missing.append("construction_methods")
            if missing:
                raise ValueError("building_to_network requires: " + ", ".join(missing))
        if self.vertical_profile:
            if len(self.vertical_profile) < 2:
                raise ValueError("vertical profile requires at least two points")
            chainages = [point.chainage_m for point in self.vertical_profile]
            if any(right <= left for left, right in zip(chainages, chainages[1:], strict=False)):
                raise ValueError("vertical profile chainage must be strictly increasing")
            vertical_required = {
                "vertical_datum": self.vertical_datum,
                "route_outside_diameter_m": self.route_outside_diameter_m,
                "minimum_vertical_clearance_m": self.minimum_vertical_clearance_m,
                "maximum_grade_percent": self.maximum_grade_percent,
            }
            missing_vertical = [name for name, value in vertical_required.items() if value is None]
            if missing_vertical:
                raise ValueError("vertical profile requires: " + ", ".join(missing_vertical))
            if any(
                crossing.chainage_m < chainages[0] or crossing.chainage_m > chainages[-1]
                for crossing in self.vertical_crossings
            ):
                raise ValueError("vertical crossing chainage must be inside the profile")
        elif self.vertical_crossings:
            raise ValueError("vertical crossings require a vertical profile")
        return self

    @field_validator("selected_dataset_version_ids")
    @classmethod
    def unique_dataset_versions(cls, value: list[UUID]) -> list[UUID]:
        if len(set(value)) != len(value):
            raise ValueError("selected dataset version IDs must be unique")
        return value

    @field_validator("connection_candidate_ids")
    @classmethod
    def unique_candidate_ids(cls, value: list[str]) -> list[str]:
        normalized = [item.strip() for item in value]
        if any(not item for item in normalized):
            raise ValueError("connection candidate IDs must not be blank")
        if len(set(normalized)) != len(normalized):
            raise ValueError("connection candidate IDs must be unique")
        return normalized


class ScenarioRevisionResponse(StrictModel):
    id: UUID
    scenario_id: UUID
    revision: int
    input_hash: str
    input_snapshot: dict[str, Any]
    created_at: datetime


class ScenarioResponse(StrictModel):
    id: UUID
    workspace_id: UUID
    project_id: UUID
    name: str
    created_at: datetime
    updated_at: datetime
    revisions: list[ScenarioRevisionResponse]


class PreflightFinding(StrictModel):
    code: str
    severity: Literal["info", "warning", "error"]
    blocking: bool
    field: str | None = None
    message: str


class PreflightResponse(StrictModel):
    scenario_revision_id: UUID
    ready: bool
    findings: list[PreflightFinding]


class ManualRouteValidationRequest(StrictModel):
    centerline_wgs84: dict[str, Any]


class ManualRouteValidationResponse(StrictModel):
    scenario_revision_id: UUID
    geometry_status: Literal["valid_in_model", "invalid_in_model", "insufficient_data"]
    validation_report: dict[str, Any]


class RunCreateRequest(StrictModel):
    algorithm: Literal["astar", "dijkstra"] = "astar"


class RunEventResponse(StrictModel):
    id: UUID
    run_id: UUID
    sequence: int
    event_type: str
    phase: str
    payload: dict[str, Any]
    created_at: datetime


class RunCancelResponse(StrictModel):
    run_id: UUID
    job_state: str
    outcome: str


class RunAcceptedResponse(StrictModel):
    run_id: UUID
    job_id: UUID
    job_state: str
    status_url: str


class RunSummaryResponse(StrictModel):
    id: UUID
    project_id: UUID
    scenario_revision_id: UUID
    job_id: UUID
    job_state: str
    outcome: str
    phase: str
    algorithm_name: str
    algorithm_version: str
    input_hash: str
    alternatives_count: int
    route_length_m: float | None
    modeled_cost: float | None
    cost_completeness: str | None
    started_at: datetime | None
    finished_at: datetime | None
    created_at: datetime


class RouteAlternativeResponse(StrictModel):
    id: UUID
    rank: int
    objective_tags: list[str]
    centerline_wgs84: dict[str, Any]
    corridor_wgs84: dict[str, Any]
    geometry_hash: str
    geometry_status: str
    metrics: dict[str, Any]
    validation_report: dict[str, Any]
    candidate_id: str | None
    segments: list[dict[str, Any]]
    quantity_items: list[dict[str, Any]]
    cost_breakdown: dict[str, Any]
    assumptions: list[Any]
    comparison: dict[str, Any]
    postprocessing_log: list[dict[str, Any]]


class RunResponse(StrictModel):
    id: UUID
    job_id: UUID
    job_state: str
    outcome: str
    phase: str
    algorithm_name: str
    algorithm_version: str
    versions_snapshot: dict[str, Any]
    parameters: dict[str, Any]
    statistics: dict[str, Any]
    runtime_library_versions: dict[str, Any]
    assumptions: list[Any]
    findings_summary: dict[str, Any]
    search_completion: str
    optimality_scope: str
    cache_info: dict[str, Any]
    started_at: datetime | None
    finished_at: datetime | None
    job_progress: dict[str, Any]
    error_code: str | None
    alternatives: list[RouteAlternativeResponse]
