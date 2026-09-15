"""Add content-addressed dataset upload provenance.

Revision ID: 20260907_0004
Revises: 20260907_0003
Create Date: 2026-09-07
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260907_0004"
down_revision: str | None = "20260907_0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    uuid = postgresql.UUID(as_uuid=True)
    jsonb = postgresql.JSONB(astext_type=sa.Text())

    def timestamps() -> tuple[sa.Column[object], sa.Column[object]]:
        return (
            sa.Column(
                "created_at",
                sa.DateTime(timezone=True),
                server_default=sa.text("now()"),
                nullable=False,
            ),
            sa.Column(
                "updated_at",
                sa.DateTime(timezone=True),
                server_default=sa.text("now()"),
                nullable=False,
            ),
        )

    op.create_table(
        "datasets",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("purpose", sa.String(64), nullable=True),
        sa.Column("id", uuid, nullable=False),
        *timestamps(),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_datasets_project_id", "datasets", ["project_id"])
    op.create_index("ix_datasets_workspace_id", "datasets", ["workspace_id"])

    op.create_table(
        "dataset_versions",
        sa.Column("dataset_id", uuid, nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("source_type", sa.String(32), nullable=False),
        sa.Column("source_name", sa.String(255), nullable=False),
        sa.Column("source_observed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("raw_hashes", jsonb, nullable=False),
        sa.Column("adapter_name", sa.String(64), nullable=True),
        sa.Column("adapter_version", sa.String(32), nullable=True),
        sa.Column("mapping_profile_id", uuid, nullable=True),
        sa.Column("mapping_profile_version", sa.Integer(), nullable=True),
        sa.Column("source_crs", sa.Text(), nullable=True),
        sa.Column("working_crs", sa.Text(), nullable=True),
        sa.Column("transform_definition", jsonb, nullable=True),
        sa.Column("transform_hash", sa.String(64), nullable=True),
        sa.Column("import_report_id", uuid, nullable=True),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("coverage_refs", jsonb, nullable=False),
        sa.Column("license_note", sa.Text(), nullable=True),
        sa.Column("contains_sensitive_infrastructure", sa.Boolean(), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *timestamps(),
        sa.CheckConstraint("version > 0", name="positive_dataset_version"),
        sa.CheckConstraint(
            "status IN ('uploaded', 'inspected', 'mapping_required', 'ready_to_validate', "
            "'validating', 'needs_review', 'ready_to_publish', 'publishing', 'published', "
            "'failed', 'rejected', 'cancelled')",
            name="valid_dataset_version_status",
        ),
        sa.ForeignKeyConstraint(["dataset_id"], ["datasets.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "dataset_id", "version", name="uq_dataset_version_number"
        ),
    )
    op.create_index("ix_dataset_versions_dataset_id", "dataset_versions", ["dataset_id"])

    op.create_table(
        "raw_artifacts",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("storage_key", sa.String(255), nullable=False),
        sa.Column("media_type", sa.String(255), nullable=True),
        sa.Column("id", uuid, nullable=False),
        *timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "workspace_id", "sha256", name="uq_raw_artifact_workspace_hash"
        ),
    )
    op.create_index("ix_raw_artifacts_workspace_id", "raw_artifacts", ["workspace_id"])

    op.create_table(
        "dataset_version_artifacts",
        sa.Column("dataset_version_id", uuid, nullable=False),
        sa.Column("raw_artifact_id", uuid, nullable=False),
        sa.Column("original_filename", sa.String(255), nullable=False),
        sa.Column("ordinal", sa.Integer(), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *timestamps(),
        sa.ForeignKeyConstraint(
            ["dataset_version_id"], ["dataset_versions.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["raw_artifact_id"], ["raw_artifacts.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "dataset_version_id",
            "raw_artifact_id",
            name="uq_dataset_version_artifact",
        ),
        sa.UniqueConstraint(
            "dataset_version_id",
            "ordinal",
            name="uq_dataset_version_artifact_ordinal",
        ),
    )
    op.create_index(
        "ix_dataset_version_artifacts_dataset_version_id",
        "dataset_version_artifacts",
        ["dataset_version_id"],
    )
    op.create_index(
        "ix_dataset_version_artifacts_raw_artifact_id",
        "dataset_version_artifacts",
        ["raw_artifact_id"],
    )

    op.create_table(
        "dataset_imports",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("dataset_version_id", uuid, nullable=False),
        sa.Column("job_id", uuid, nullable=False),
        sa.Column("state", sa.String(32), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *timestamps(),
        sa.CheckConstraint(
            "state IN ('uploaded', 'inspecting', 'mapping_required', 'ready_to_validate', "
            "'validating', 'needs_review', 'ready_to_publish', 'publishing', 'published', "
            "'failed', 'rejected', 'cancelled')",
            name="valid_dataset_import_state",
        ),
        sa.ForeignKeyConstraint(
            ["dataset_version_id"], ["dataset_versions.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(["job_id"], ["jobs.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("dataset_version_id"),
        sa.UniqueConstraint("job_id"),
    )
    op.create_index("ix_dataset_imports_project_id", "dataset_imports", ["project_id"])
    op.create_index("ix_dataset_imports_workspace_id", "dataset_imports", ["workspace_id"])


def downgrade() -> None:
    op.drop_table("dataset_imports")
    op.drop_table("dataset_version_artifacts")
    op.drop_table("raw_artifacts")
    op.drop_table("dataset_versions")
    op.drop_table("datasets")
