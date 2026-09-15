"""Create PostGIS extension and M0 system tables.

Revision ID: 20260907_0001
Revises: None
Create Date: 2026-09-07
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260907_0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS postgis")
    op.create_table(
        "workspaces",
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_workspaces")),
    )
    op.create_table(
        "users",
        sa.Column("workspace_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("email", sa.String(length=320), nullable=False),
        sa.Column("password_hash", sa.String(length=255), nullable=False),
        sa.Column("role", sa.String(length=16), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("role IN ('viewer', 'editor', 'admin')", name=op.f("ck_users_valid_user_role")),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], name=op.f("fk_users_workspace_id_workspaces"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
        sa.UniqueConstraint("workspace_id", "email", name="uq_users_workspace_email"),
    )
    op.create_index(op.f("ix_users_workspace_id"), "users", ["workspace_id"], unique=False)
    op.create_table(
        "projects",
        sa.Column("workspace_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=120), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("working_crs", sa.Text(), nullable=True),
        sa.Column("crs_confirmed", sa.Boolean(), nullable=False),
        sa.Column("source_mode", sa.String(length=16), nullable=False),
        sa.Column("current_revision", sa.Integer(), nullable=False),
        sa.Column("bbox_wgs84", geoalchemy2.types.Geometry(geometry_type="POLYGON", srid=4326, spatial_index=False), nullable=True),
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("current_revision > 0", name=op.f("ck_projects_positive_revision")),
        sa.CheckConstraint("source_mode IN ('synthetic', 'mixed', 'provided')", name=op.f("ck_projects_source_mode")),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], name=op.f("fk_projects_workspace_id_workspaces"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_projects")),
    )
    op.create_index(op.f("ix_projects_workspace_id"), "projects", ["workspace_id"], unique=False)
    op.create_index("ix_projects_bbox_wgs84_gist", "projects", ["bbox_wgs84"], unique=False, postgresql_using="gist")
    op.create_table(
        "jobs",
        sa.Column("workspace_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("state", sa.String(length=24), nullable=False),
        sa.Column("phase", sa.String(length=64), nullable=True),
        sa.Column("attempt", sa.Integer(), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("result", postgresql.JSONB(astext_type=sa.Text()), nullable=True),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("heartbeat_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.CheckConstraint("state IN ('queued', 'running', 'cancel_requested', 'succeeded', 'partial', 'failed', 'cancelled')", name=op.f("ck_jobs_valid_job_state")),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], name=op.f("fk_jobs_workspace_id_workspaces"), ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_jobs")),
    )
    op.create_index(op.f("ix_jobs_workspace_id"), "jobs", ["workspace_id"], unique=False)
    op.create_index("ix_jobs_state_created", "jobs", ["state", "created_at"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_jobs_state_created", table_name="jobs")
    op.drop_index(op.f("ix_jobs_workspace_id"), table_name="jobs")
    op.drop_table("jobs")
    op.drop_index("ix_projects_bbox_wgs84_gist", table_name="projects", postgresql_using="gist")
    op.drop_index(op.f("ix_projects_workspace_id"), table_name="projects")
    op.drop_table("projects")
    op.drop_index(op.f("ix_users_workspace_id"), table_name="users")
    op.drop_table("users")
    op.drop_table("workspaces")

