"""Add staging quarantine and canonical feature snapshots.

Revision ID: 20260908_0007
Revises: 20260907_0006
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0007"
down_revision: str | None = "20260907_0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _timestamps() -> list[sa.Column[object]]:
    return [
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
    ]


def upgrade() -> None:
    uuid = postgresql.UUID(as_uuid=True)
    jsonb = postgresql.JSONB(astext_type=sa.Text())
    geometry = geoalchemy2.Geometry(
        geometry_type="GEOMETRY",
        srid=4326,
        spatial_index=False,
    )
    op.add_column(
        "dataset_versions",
        sa.Column("publication_policy", jsonb, nullable=True),
    )
    op.create_table(
        "staging_features",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("dataset_import_id", uuid, nullable=False),
        sa.Column("source_row", sa.BigInteger(), nullable=False),
        sa.Column("source_id", sa.String(500), nullable=True),
        sa.Column("source_layer", sa.String(255), nullable=False),
        sa.Column("target_kind", sa.String(32), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("issue_codes", jsonb, nullable=False),
        sa.Column("raw_properties", jsonb, nullable=False),
        sa.Column("attributes", jsonb, nullable=False),
        sa.Column("geometry_wgs84", geometry, nullable=True),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint(
            "status IN ('accepted', 'quarantined', 'rejected')",
            name="valid_staging_feature_status",
        ),
        sa.ForeignKeyConstraint(["dataset_import_id"], ["dataset_imports.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("dataset_import_id", "source_row", name="uq_staging_import_source_row"),
    )
    op.create_index(
        "ix_staging_features_dataset_import_id", "staging_features", ["dataset_import_id"]
    )
    op.create_index("ix_staging_features_workspace_id", "staging_features", ["workspace_id"])
    op.create_index(
        "ix_staging_features_geometry_gist",
        "staging_features",
        ["geometry_wgs84"],
        postgresql_using="gist",
    )

    op.create_table(
        "canonical_features",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("dataset_version_id", uuid, nullable=False),
        sa.Column("staging_feature_id", uuid, nullable=False),
        sa.Column("logical_id", uuid, nullable=False),
        sa.Column("kind", sa.String(32), nullable=False),
        sa.Column("source_id", sa.String(500), nullable=False),
        sa.Column("source_layer", sa.String(255), nullable=False),
        sa.Column("source_type", sa.String(32), nullable=False),
        sa.Column("lifecycle_status", sa.String(32), nullable=False),
        sa.Column("quality_flags", jsonb, nullable=False),
        sa.Column("raw_properties", jsonb, nullable=False),
        sa.Column("attributes", jsonb, nullable=False),
        sa.Column("geometry_wgs84", geometry, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(
            ["dataset_version_id"], ["dataset_versions.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["staging_feature_id"], ["staging_features.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("staging_feature_id"),
        sa.UniqueConstraint(
            "dataset_version_id",
            "kind",
            "source_layer",
            "source_id",
            name="uq_canonical_feature_source_identity",
        ),
    )
    op.create_index(
        "ix_canonical_features_dataset_version_id",
        "canonical_features",
        ["dataset_version_id"],
    )
    op.create_index("ix_canonical_features_logical_id", "canonical_features", ["logical_id"])
    op.create_index("ix_canonical_features_project_id", "canonical_features", ["project_id"])
    op.create_index("ix_canonical_features_workspace_id", "canonical_features", ["workspace_id"])
    op.create_index(
        "ix_canonical_features_geometry_gist",
        "canonical_features",
        ["geometry_wgs84"],
        postgresql_using="gist",
    )


def downgrade() -> None:
    op.drop_table("canonical_features")
    op.drop_table("staging_features")
    op.drop_column("dataset_versions", "publication_policy")
