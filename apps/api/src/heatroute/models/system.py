from datetime import datetime
from typing import Any
from uuid import UUID

from geoalchemy2 import Geometry
from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Identity,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from heatroute.models.base import Base, TimestampMixin, UUIDPrimaryKeyMixin


class Workspace(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "workspaces"

    name: Mapped[str] = mapped_column(String(120), nullable=False)


class User(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "users"
    __table_args__ = (
        CheckConstraint("role IN ('viewer', 'editor', 'admin')", name="valid_user_role"),
        UniqueConstraint("workspace_id", "email", name="uq_users_workspace_email"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


class AuthSession(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "auth_sessions"

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    user_id: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    csrf_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class AuditEvent(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "audit_events"
    __table_args__ = (
        Index("ix_audit_events_workspace_created", "workspace_id", "created_at"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    actor_user_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    request_id: Mapped[str] = mapped_column(String(128), nullable=False)
    method: Mapped[str] = mapped_column(String(8), nullable=False)
    path: Mapped[str] = mapped_column(String(500), nullable=False)
    status_code: Mapped[int] = mapped_column(Integer, nullable=False)
    action: Mapped[str] = mapped_column(String(32), nullable=False)
    metadata_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)


class Project(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "projects"
    __table_args__ = (
        CheckConstraint("source_mode IN ('synthetic', 'mixed', 'provided')", name="source_mode"),
        CheckConstraint("current_revision > 0", name="positive_revision"),
        Index("ix_projects_bbox_wgs84_gist", "bbox_wgs84", postgresql_using="gist"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    working_crs: Mapped[str | None] = mapped_column(Text)
    crs_confirmed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    source_mode: Mapped[str] = mapped_column(String(16), nullable=False, default="synthetic")
    current_revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    bbox_wgs84: Mapped[Any | None] = mapped_column(
        Geometry(geometry_type="POLYGON", srid=4326, spatial_index=False), nullable=True
    )


class Dataset(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "datasets"

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    purpose: Mapped[str | None] = mapped_column(String(64))


class DatasetVersion(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "dataset_versions"
    __table_args__ = (
        UniqueConstraint("dataset_id", "version", name="uq_dataset_version_number"),
        CheckConstraint("version > 0", name="positive_dataset_version"),
        CheckConstraint(
            "status IN ('uploaded', 'inspected', 'mapping_required', 'ready_to_validate', "
            "'validating', 'needs_review', 'ready_to_publish', 'publishing', 'published', "
            "'failed', 'rejected', 'cancelled')",
            name="valid_dataset_version_status",
        ),
    )

    dataset_id: Mapped[UUID] = mapped_column(
        ForeignKey("datasets.id", ondelete="CASCADE"), nullable=False, index=True
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="uploaded")
    source_type: Mapped[str] = mapped_column(String(32), nullable=False)
    source_name: Mapped[str] = mapped_column(String(255), nullable=False)
    source_observed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    raw_hashes: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    adapter_name: Mapped[str | None] = mapped_column(String(64))
    adapter_version: Mapped[str | None] = mapped_column(String(32))
    mapping_profile_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("mapping_profiles.id", ondelete="RESTRICT"), nullable=True
    )
    mapping_profile_version: Mapped[int | None] = mapped_column(Integer)
    source_crs: Mapped[str | None] = mapped_column(Text)
    working_crs: Mapped[str | None] = mapped_column(Text)
    transform_definition: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    transform_hash: Mapped[str | None] = mapped_column(String(64))
    import_report_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("import_reports.id", ondelete="SET NULL"), nullable=True
    )
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    publication_policy: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    coverage_refs: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    license_note: Mapped[str | None] = mapped_column(Text)
    contains_sensitive_infrastructure: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False
    )


class RawArtifact(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "raw_artifacts"
    __table_args__ = (
        UniqueConstraint("workspace_id", "sha256", name="uq_raw_artifact_workspace_hash"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    storage_key: Mapped[str] = mapped_column(String(255), nullable=False)
    media_type: Mapped[str | None] = mapped_column(String(255))


class MappingProfile(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "mapping_profiles"
    __table_args__ = (CheckConstraint("current_revision > 0", name="positive_revision"),)

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    current_revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class MappingProfileVersion(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "mapping_profile_versions"
    __table_args__ = (
        UniqueConstraint("profile_id", "revision", name="uq_mapping_profile_revision"),
        CheckConstraint("revision > 0", name="positive_revision"),
    )

    profile_id: Mapped[UUID] = mapped_column(
        ForeignKey("mapping_profiles.id", ondelete="CASCADE"), nullable=False, index=True
    )
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    definition_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    definition: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)


class RuleProfile(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "rule_profiles"
    __table_args__ = (CheckConstraint("current_revision > 0", name="positive_revision"),)

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    current_revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class RuleProfileVersion(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "rule_profile_versions"
    __table_args__ = (
        UniqueConstraint("profile_id", "revision", name="uq_rule_profile_revision"),
        CheckConstraint("revision > 0", name="positive_revision"),
        CheckConstraint("status IN ('demo', 'draft', 'reviewed')", name="valid_status"),
    )

    profile_id: Mapped[UUID] = mapped_column(
        ForeignKey("rule_profiles.id", ondelete="CASCADE"), nullable=False, index=True
    )
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    definition_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    definition: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)


class CostCatalog(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "cost_catalogs"
    __table_args__ = (CheckConstraint("current_revision > 0", name="positive_revision"),)

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    current_revision: Mapped[int] = mapped_column(Integer, nullable=False, default=1)


class CostCatalogVersion(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "cost_catalog_versions"
    __table_args__ = (
        UniqueConstraint("catalog_id", "revision", name="uq_cost_catalog_revision"),
        CheckConstraint("revision > 0", name="positive_revision"),
        CheckConstraint(
            "status IN ('synthetic', 'draft', 'reviewed')", name="valid_status"
        ),
    )

    catalog_id: Mapped[UUID] = mapped_column(
        ForeignKey("cost_catalogs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    definition_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    definition: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)


class DatasetVersionArtifact(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "dataset_version_artifacts"
    __table_args__ = (
        UniqueConstraint(
            "dataset_version_id", "ordinal", name="uq_dataset_version_artifact_ordinal"
        ),
        UniqueConstraint(
            "dataset_version_id", "raw_artifact_id", name="uq_dataset_version_artifact"
        ),
    )

    dataset_version_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_versions.id", ondelete="CASCADE"), nullable=False, index=True
    )
    raw_artifact_id: Mapped[UUID] = mapped_column(
        ForeignKey("raw_artifacts.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    original_filename: Mapped[str] = mapped_column(String(255), nullable=False)
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False, default=0)


class DatasetLayerVersion(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "dataset_layer_versions"
    __table_args__ = (
        UniqueConstraint("dataset_version_id", "name", name="uq_dataset_layer_version_name"),
        UniqueConstraint("dataset_version_id", "ordinal", name="uq_dataset_layer_version_ordinal"),
        CheckConstraint(
            "status IN ('inspected', 'mapped', 'validated', 'published', 'not_selected')",
            name="valid_dataset_layer_version_status",
        ),
    )

    dataset_version_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_versions.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False)
    geometry_type: Mapped[str | None] = mapped_column(String(64))
    source_crs: Mapped[str | None] = mapped_column(Text)
    mapped_kind: Mapped[str | None] = mapped_column(String(32))
    feature_count: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="inspected")
    metadata_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)


class Job(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "jobs"
    __table_args__ = (
        CheckConstraint(
            "state IN ('queued', 'running', 'cancel_requested', 'succeeded', "
            "'partial', 'failed', 'cancelled')",
            name="valid_job_state",
        ),
        Index("ix_jobs_state_created", "state", "created_at"),
        Index("ix_jobs_lease_expiry", "state", "lease_expires_at"),
    )

    workspace_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=True, index=True
    )
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    state: Mapped[str] = mapped_column(String(24), nullable=False, default="queued")
    phase: Mapped[str | None] = mapped_column(String(64))
    attempt: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    result: Mapped[dict[str, Any] | None] = mapped_column(JSONB)
    error_code: Mapped[str | None] = mapped_column(String(64))
    heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    progress_current: Mapped[int | None] = mapped_column(BigInteger)
    progress_total: Mapped[int | None] = mapped_column(BigInteger)
    progress_unit: Mapped[str | None] = mapped_column(String(32))
    timings: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    lease_owner: Mapped[str | None] = mapped_column(String(128))
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    retryable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    error_context: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    max_attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=3)


class DatasetImport(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "dataset_imports"
    __table_args__ = (
        CheckConstraint(
            "state IN ('uploaded', 'inspecting', 'mapping_required', 'ready_to_validate', "
            "'validating', 'needs_review', 'ready_to_publish', 'publishing', 'published', "
            "'failed', 'rejected', 'cancelled')",
            name="valid_dataset_import_state",
        ),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    dataset_version_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_versions.id", ondelete="CASCADE"), nullable=False, unique=True
    )
    job_id: Mapped[UUID] = mapped_column(
        ForeignKey("jobs.id", ondelete="RESTRICT"), nullable=False, unique=True
    )
    state: Mapped[str] = mapped_column(String(32), nullable=False, default="uploaded")


class ImportReport(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "import_reports"

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    dataset_import_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_imports.id", ondelete="CASCADE"), nullable=False, unique=True
    )
    stage: Mapped[str] = mapped_column(String(32), nullable=False, default="inspection")
    counts: Mapped[dict[str, int]] = mapped_column(JSONB, nullable=False, default=dict)
    layers: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False, default=list)
    errors: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False, default=list)
    warnings: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False, default=list)
    publish_blockers: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )
    missing_value_distribution: Mapped[dict[str, int]] = mapped_column(
        JSONB, nullable=False, default=dict
    )
    source_references: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )
    repairs: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False, default=list)
    crs_diagnostics: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )
    topology_diagnostics: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )
    coverage_diagnostics: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )


class StagingFeature(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "staging_features"
    __table_args__ = (
        CheckConstraint(
            "status IN ('accepted', 'quarantined', 'rejected')",
            name="valid_staging_feature_status",
        ),
        UniqueConstraint("dataset_import_id", "source_row", name="uq_staging_import_source_row"),
        Index("ix_staging_features_geometry_gist", "geometry_wgs84", postgresql_using="gist"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    dataset_import_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_imports.id", ondelete="CASCADE"), nullable=False, index=True
    )
    source_row: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source_id: Mapped[str | None] = mapped_column(String(500))
    source_layer: Mapped[str] = mapped_column(String(255), nullable=False)
    target_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    issue_codes: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    raw_properties: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    attributes: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    geometry_wgs84: Mapped[Any | None] = mapped_column(
        Geometry(geometry_type="GEOMETRY", srid=4326, spatial_index=False), nullable=True
    )


class CanonicalFeature(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "canonical_features"
    __table_args__ = (
        UniqueConstraint(
            "dataset_version_id",
            "kind",
            "source_layer",
            "source_id",
            name="uq_canonical_feature_source_identity",
        ),
        Index("ix_canonical_features_geometry_gist", "geometry_wgs84", postgresql_using="gist"),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    dataset_version_id: Mapped[UUID] = mapped_column(
        ForeignKey("dataset_versions.id", ondelete="CASCADE"), nullable=False, index=True
    )
    staging_feature_id: Mapped[UUID] = mapped_column(
        ForeignKey("staging_features.id", ondelete="RESTRICT"), nullable=False, unique=True
    )
    logical_id: Mapped[UUID] = mapped_column(nullable=False, index=True)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    source_id: Mapped[str] = mapped_column(String(500), nullable=False)
    source_layer: Mapped[str] = mapped_column(String(255), nullable=False)
    source_type: Mapped[str] = mapped_column(String(32), nullable=False)
    lifecycle_status: Mapped[str] = mapped_column(String(32), nullable=False, default="unknown")
    quality_flags: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    raw_properties: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    attributes: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    geometry_wgs84: Mapped[Any] = mapped_column(
        Geometry(geometry_type="GEOMETRY", srid=4326, spatial_index=False), nullable=False
    )


class Scenario(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "scenarios"

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)


class ScenarioRevision(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "scenario_revisions"
    __table_args__ = (
        UniqueConstraint("scenario_id", "revision", name="uq_scenario_revision_number"),
        CheckConstraint("revision > 0", name="positive_scenario_revision"),
    )

    scenario_id: Mapped[UUID] = mapped_column(
        ForeignKey("scenarios.id", ondelete="CASCADE"), nullable=False, index=True
    )
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    input_snapshot: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)


class CalculationRun(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "calculation_runs"
    __table_args__ = (
        UniqueConstraint("workspace_id", "idempotency_key", name="uq_run_idempotency"),
        CheckConstraint(
            "job_state IN ('queued', 'running', 'cancel_requested', 'succeeded', "
            "'partial', 'failed', 'cancelled')",
            name="valid_run_job_state",
        ),
    )

    workspace_id: Mapped[UUID] = mapped_column(
        ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False, index=True
    )
    project_id: Mapped[UUID] = mapped_column(
        ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True
    )
    scenario_revision_id: Mapped[UUID] = mapped_column(
        ForeignKey("scenario_revisions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    job_id: Mapped[UUID] = mapped_column(
        ForeignKey("jobs.id", ondelete="RESTRICT"), nullable=False, unique=True
    )
    input_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128), nullable=False)
    job_state: Mapped[str] = mapped_column(String(24), nullable=False, default="queued")
    outcome: Mapped[str] = mapped_column(String(32), nullable=False, default="pending")
    phase: Mapped[str] = mapped_column(String(64), nullable=False, default="queued")
    algorithm_name: Mapped[str] = mapped_column(String(32), nullable=False)
    algorithm_version: Mapped[str] = mapped_column(String(32), nullable=False, default="grid-v1")
    versions_snapshot: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    parameters: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    statistics: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    error_code: Mapped[str | None] = mapped_column(String(64))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    runtime_library_versions: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict
    )
    assumptions: Mapped[list[Any]] = mapped_column(JSONB, nullable=False, default=list)
    findings_summary: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict
    )
    search_completion: Mapped[str] = mapped_column(
        String(32), nullable=False, default="not_started"
    )
    optimality_scope: Mapped[str] = mapped_column(
        String(32), nullable=False, default="not_applicable"
    )
    cache_info: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)


class RouteAlternative(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "route_alternatives"
    __table_args__ = (UniqueConstraint("run_id", "geometry_hash", name="uq_run_geometry_hash"),)

    run_id: Mapped[UUID] = mapped_column(
        ForeignKey("calculation_runs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    rank: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    objective_tags: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    centerline_wgs84: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    corridor_wgs84: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    geometry_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    geometry_status: Mapped[str] = mapped_column(String(32), nullable=False)
    metrics: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    validation_report: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    candidate_id: Mapped[str | None] = mapped_column(String(500))
    segments: Mapped[list[dict[str, Any]]] = mapped_column(JSONB, nullable=False, default=list)
    quantity_items: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )
    cost_breakdown: Mapped[dict[str, Any]] = mapped_column(
        JSONB, nullable=False, default=dict
    )
    assumptions: Mapped[list[Any]] = mapped_column(JSONB, nullable=False, default=list)
    comparison: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)
    postprocessing_log: Mapped[list[dict[str, Any]]] = mapped_column(
        JSONB, nullable=False, default=list
    )


class RunEvent(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "run_events"

    run_id: Mapped[UUID] = mapped_column(
        ForeignKey("calculation_runs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    sequence: Mapped[int] = mapped_column(
        BigInteger, Identity(), nullable=False, unique=True, index=True
    )
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    phase: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False, default=dict)


class OutboxEvent(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "outbox_events"
    __table_args__ = (
        CheckConstraint("state IN ('pending', 'published')", name="valid_outbox_state"),
    )

    aggregate_type: Mapped[str] = mapped_column(String(32), nullable=False)
    aggregate_id: Mapped[UUID] = mapped_column(nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    payload: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    state: Mapped[str] = mapped_column(String(16), nullable=False, default="pending", index=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_error: Mapped[str | None] = mapped_column(String(255))
