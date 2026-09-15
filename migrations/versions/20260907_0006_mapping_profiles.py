"""Add immutable mapping profile revisions.

Revision ID: 20260907_0006
Revises: 20260907_0005
Create Date: 2026-09-07
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260907_0006"
down_revision: str | None = "20260907_0005"
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
    op.create_table(
        "mapping_profiles",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("current_revision", sa.Integer(), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("current_revision > 0", name="positive_revision"),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_mapping_profiles_project_id", "mapping_profiles", ["project_id"])
    op.create_index("ix_mapping_profiles_workspace_id", "mapping_profiles", ["workspace_id"])
    op.create_table(
        "mapping_profile_versions",
        sa.Column("profile_id", uuid, nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("definition_hash", sa.String(64), nullable=False),
        sa.Column("definition", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("revision > 0", name="positive_revision"),
        sa.ForeignKeyConstraint(
            ["profile_id"], ["mapping_profiles.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("profile_id", "revision", name="uq_mapping_profile_revision"),
    )
    op.create_index(
        "ix_mapping_profile_versions_profile_id",
        "mapping_profile_versions",
        ["profile_id"],
    )
    op.create_foreign_key(
        "fk_dataset_versions_mapping_profile_id_mapping_profiles",
        "dataset_versions",
        "mapping_profiles",
        ["mapping_profile_id"],
        ["id"],
        ondelete="RESTRICT",
    )


def downgrade() -> None:
    op.drop_constraint(
        "fk_dataset_versions_mapping_profile_id_mapping_profiles",
        "dataset_versions",
        type_="foreignkey",
    )
    op.drop_table("mapping_profile_versions")
    op.drop_table("mapping_profiles")
