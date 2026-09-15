"""Persist scenarios, runs, alternatives and outbox events.

Revision ID: 20260907_0002
Revises: 20260907_0001
Create Date: 2026-09-07
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260907_0002"
down_revision: str | None = "20260907_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _timestamps() -> list[sa.Column[object]]:
    return [
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    ]


def upgrade() -> None:
    uuid = postgresql.UUID(as_uuid=True)
    jsonb = postgresql.JSONB(astext_type=sa.Text())
    op.create_table(
        "scenarios",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_scenarios_workspace_id", "scenarios", ["workspace_id"])
    op.create_index("ix_scenarios_project_id", "scenarios", ["project_id"])
    op.create_table(
        "scenario_revisions",
        sa.Column("scenario_id", uuid, nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("input_hash", sa.String(64), nullable=False),
        sa.Column("input_snapshot", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("revision > 0", name="ck_scenario_revisions_positive_scenario_revision"),
        sa.ForeignKeyConstraint(["scenario_id"], ["scenarios.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("scenario_id", "revision", name="uq_scenario_revision_number"),
    )
    op.create_index("ix_scenario_revisions_scenario_id", "scenario_revisions", ["scenario_id"])
    op.create_table(
        "calculation_runs",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("project_id", uuid, nullable=False),
        sa.Column("scenario_revision_id", uuid, nullable=False),
        sa.Column("job_id", uuid, nullable=False),
        sa.Column("input_hash", sa.String(64), nullable=False),
        sa.Column("idempotency_key", sa.String(128), nullable=False),
        sa.Column("job_state", sa.String(24), nullable=False),
        sa.Column("outcome", sa.String(32), nullable=False),
        sa.Column("phase", sa.String(64), nullable=False),
        sa.Column("algorithm_name", sa.String(32), nullable=False),
        sa.Column("algorithm_version", sa.String(32), nullable=False),
        sa.Column("parameters", jsonb, nullable=False),
        sa.Column("statistics", jsonb, nullable=False),
        sa.Column("error_code", sa.String(64), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("job_state IN ('queued','running','cancel_requested','succeeded','partial','failed','cancelled')", name="ck_calculation_runs_valid_run_job_state"),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["project_id"], ["projects.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["scenario_revision_id"], ["scenario_revisions.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["job_id"], ["jobs.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("job_id"),
        sa.UniqueConstraint("workspace_id", "idempotency_key", name="uq_run_idempotency"),
    )
    for column in ("workspace_id", "project_id", "scenario_revision_id"):
        op.create_index(f"ix_calculation_runs_{column}", "calculation_runs", [column])
    op.create_table(
        "route_alternatives",
        sa.Column("run_id", uuid, nullable=False),
        sa.Column("rank", sa.Integer(), nullable=False),
        sa.Column("objective_tags", jsonb, nullable=False),
        sa.Column("centerline_wgs84", jsonb, nullable=False),
        sa.Column("corridor_wgs84", jsonb, nullable=False),
        sa.Column("geometry_hash", sa.String(64), nullable=False),
        sa.Column("geometry_status", sa.String(32), nullable=False),
        sa.Column("metrics", jsonb, nullable=False),
        sa.Column("validation_report", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["run_id"], ["calculation_runs.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("run_id", "geometry_hash", name="uq_run_geometry_hash"),
    )
    op.create_index("ix_route_alternatives_run_id", "route_alternatives", ["run_id"])
    op.create_table(
        "outbox_events",
        sa.Column("aggregate_type", sa.String(32), nullable=False),
        sa.Column("aggregate_id", uuid, nullable=False),
        sa.Column("event_type", sa.String(64), nullable=False),
        sa.Column("payload", jsonb, nullable=False),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_error", sa.String(255), nullable=True),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("state IN ('pending','published')", name="ck_outbox_events_valid_outbox_state"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_outbox_events_aggregate_id", "outbox_events", ["aggregate_id"])
    op.create_index("ix_outbox_events_state", "outbox_events", ["state"])


def downgrade() -> None:
    op.drop_table("outbox_events")
    op.drop_table("route_alternatives")
    op.drop_table("calculation_runs")
    op.drop_table("scenario_revisions")
    op.drop_table("scenarios")
