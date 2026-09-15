"""Add immutable rule profile versions.

Revision ID: 20260908_0012
Revises: 20260908_0011
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0012"
down_revision: str | None = "20260908_0011"
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
        "rule_profiles",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("current_revision", sa.Integer(), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("current_revision > 0", name="positive_revision"),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(op.f("ix_rule_profiles_project_id"), "rule_profiles", ["project_id"])
    op.create_index(
        op.f("ix_rule_profiles_workspace_id"), "rule_profiles", ["workspace_id"]
    )
    op.create_table(
        "rule_profile_versions",
        sa.Column("profile_id", uuid, nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("definition_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("definition", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("revision > 0", name="positive_revision"),
        sa.CheckConstraint("status IN ('demo', 'draft', 'reviewed')", name="valid_status"),
        sa.ForeignKeyConstraint(["profile_id"], ["rule_profiles.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("profile_id", "revision", name="uq_rule_profile_revision"),
    )
    op.create_index(
        op.f("ix_rule_profile_versions_profile_id"),
        "rule_profile_versions",
        ["profile_id"],
    )


def downgrade() -> None:
    op.drop_index(
        op.f("ix_rule_profile_versions_profile_id"), table_name="rule_profile_versions"
    )
    op.drop_table("rule_profile_versions")
    op.drop_index(op.f("ix_rule_profiles_workspace_id"), table_name="rule_profiles")
    op.drop_index(op.f("ix_rule_profiles_project_id"), table_name="rule_profiles")
    op.drop_table("rule_profiles")
