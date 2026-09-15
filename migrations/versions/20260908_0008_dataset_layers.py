"""Add durable dataset layer versions.

Revision ID: 20260908_0008
Revises: 20260908_0007
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0008"
down_revision: str | None = "20260908_0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "dataset_layer_versions",
        sa.Column("dataset_version_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("ordinal", sa.Integer(), nullable=False),
        sa.Column("geometry_type", sa.String(length=64), nullable=True),
        sa.Column("source_crs", sa.Text(), nullable=True),
        sa.Column("mapped_kind", sa.String(length=32), nullable=True),
        sa.Column("feature_count", sa.BigInteger(), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column(
            "metadata_json",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
        ),
        sa.Column("id", sa.Uuid(), nullable=False),
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
        sa.CheckConstraint(
            "status IN ('inspected', 'mapped', 'validated', 'published', 'not_selected')",
            name="valid_dataset_layer_version_status",
        ),
        sa.ForeignKeyConstraint(
            ["dataset_version_id"], ["dataset_versions.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "dataset_version_id", "name", name="uq_dataset_layer_version_name"
        ),
        sa.UniqueConstraint(
            "dataset_version_id", "ordinal", name="uq_dataset_layer_version_ordinal"
        ),
    )
    op.create_index(
        op.f("ix_dataset_layer_versions_dataset_version_id"),
        "dataset_layer_versions",
        ["dataset_version_id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index(
        op.f("ix_dataset_layer_versions_dataset_version_id"),
        table_name="dataset_layer_versions",
    )
    op.drop_table("dataset_layer_versions")
