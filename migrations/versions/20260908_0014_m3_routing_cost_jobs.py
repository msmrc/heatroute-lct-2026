"""Add M3 cost catalogs, run passport fields and durable job leases.

Revision ID: 20260908_0014
Revises: 20260908_0013
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0014"
down_revision: str | None = "20260908_0013"
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
        "cost_catalogs",
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
    op.create_index(op.f("ix_cost_catalogs_project_id"), "cost_catalogs", ["project_id"])
    op.create_index(
        op.f("ix_cost_catalogs_workspace_id"), "cost_catalogs", ["workspace_id"]
    )
    op.create_table(
        "cost_catalog_versions",
        sa.Column("catalog_id", uuid, nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("definition_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("definition", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
        *_timestamps(),
        sa.CheckConstraint("revision > 0", name="positive_revision"),
        sa.CheckConstraint(
            "status IN ('synthetic', 'draft', 'reviewed')", name="valid_status"
        ),
        sa.ForeignKeyConstraint(
            ["catalog_id"], ["cost_catalogs.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("catalog_id", "revision", name="uq_cost_catalog_revision"),
    )
    op.create_index(
        op.f("ix_cost_catalog_versions_catalog_id"),
        "cost_catalog_versions",
        ["catalog_id"],
    )

    op.add_column("jobs", sa.Column("progress_current", sa.BigInteger(), nullable=True))
    op.add_column("jobs", sa.Column("progress_total", sa.BigInteger(), nullable=True))
    op.add_column("jobs", sa.Column("progress_unit", sa.String(length=32), nullable=True))
    op.add_column(
        "jobs",
        sa.Column("timings", jsonb, server_default=sa.text("'{}'::jsonb"), nullable=False),
    )
    op.add_column("jobs", sa.Column("lease_owner", sa.String(length=128), nullable=True))
    op.add_column("jobs", sa.Column("lease_expires_at", sa.DateTime(timezone=True)))
    op.add_column(
        "jobs",
        sa.Column("retryable", sa.Boolean(), server_default=sa.true(), nullable=False),
    )
    op.add_column(
        "jobs",
        sa.Column(
            "error_context", jsonb, server_default=sa.text("'{}'::jsonb"), nullable=False
        ),
    )
    op.add_column(
        "jobs",
        sa.Column("max_attempts", sa.Integer(), server_default="3", nullable=False),
    )
    op.create_index("ix_jobs_lease_expiry", "jobs", ["state", "lease_expires_at"])

    run_columns: list[sa.Column[object]] = [
        sa.Column(
            "runtime_library_versions",
            jsonb,
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "assumptions",
            jsonb,
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "findings_summary",
            jsonb,
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "search_completion",
            sa.String(length=32),
            server_default="not_started",
            nullable=False,
        ),
        sa.Column(
            "optimality_scope",
            sa.String(length=32),
            server_default="not_applicable",
            nullable=False,
        ),
        sa.Column(
            "cache_info",
            jsonb,
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    ]
    for column in run_columns:
        op.add_column("calculation_runs", column)

    alternative_columns: list[sa.Column[object]] = [
        sa.Column("candidate_id", sa.String(length=500)),
        sa.Column(
            "segments", jsonb, server_default=sa.text("'[]'::jsonb"), nullable=False
        ),
        sa.Column(
            "quantity_items",
            jsonb,
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "cost_breakdown",
            jsonb,
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "assumptions", jsonb, server_default=sa.text("'[]'::jsonb"), nullable=False
        ),
        sa.Column(
            "comparison", jsonb, server_default=sa.text("'{}'::jsonb"), nullable=False
        ),
        sa.Column(
            "postprocessing_log",
            jsonb,
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
    ]
    for column in alternative_columns:
        op.add_column("route_alternatives", column)

    for table, columns in (
        (
            "jobs",
            ("timings", "retryable", "error_context", "max_attempts"),
        ),
        (
            "calculation_runs",
            (
                "runtime_library_versions",
                "assumptions",
                "findings_summary",
                "search_completion",
                "optimality_scope",
                "cache_info",
            ),
        ),
        (
            "route_alternatives",
            (
                "segments",
                "quantity_items",
                "cost_breakdown",
                "assumptions",
                "comparison",
                "postprocessing_log",
            ),
        ),
    ):
        for column in columns:
            op.alter_column(table, column, server_default=None)


def downgrade() -> None:
    for column in (
        "postprocessing_log",
        "comparison",
        "assumptions",
        "cost_breakdown",
        "quantity_items",
        "segments",
        "candidate_id",
    ):
        op.drop_column("route_alternatives", column)
    for column in (
        "cache_info",
        "optimality_scope",
        "search_completion",
        "findings_summary",
        "assumptions",
        "runtime_library_versions",
    ):
        op.drop_column("calculation_runs", column)
    op.drop_index("ix_jobs_lease_expiry", table_name="jobs")
    for column in (
        "max_attempts",
        "error_context",
        "retryable",
        "lease_expires_at",
        "lease_owner",
        "timings",
        "progress_unit",
        "progress_total",
        "progress_current",
    ):
        op.drop_column("jobs", column)
    op.drop_index(
        op.f("ix_cost_catalog_versions_catalog_id"),
        table_name="cost_catalog_versions",
    )
    op.drop_table("cost_catalog_versions")
    op.drop_index(op.f("ix_cost_catalogs_workspace_id"), table_name="cost_catalogs")
    op.drop_index(op.f("ix_cost_catalogs_project_id"), table_name="cost_catalogs")
    op.drop_table("cost_catalogs")
