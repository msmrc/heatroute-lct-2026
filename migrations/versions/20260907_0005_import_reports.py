"""Persist dataset inspection reports.

Revision ID: 20260907_0005
Revises: 20260907_0004
Create Date: 2026-09-07
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260907_0005"
down_revision: str | None = "20260907_0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    uuid = postgresql.UUID(as_uuid=True)
    jsonb = postgresql.JSONB(astext_type=sa.Text())
    op.create_table(
        "import_reports",
        sa.Column("workspace_id", uuid, nullable=False),
        sa.Column("dataset_import_id", uuid, nullable=False),
        sa.Column("stage", sa.String(32), nullable=False),
        sa.Column("counts", jsonb, nullable=False),
        sa.Column("layers", jsonb, nullable=False),
        sa.Column("errors", jsonb, nullable=False),
        sa.Column("warnings", jsonb, nullable=False),
        sa.Column("publish_blockers", jsonb, nullable=False),
        sa.Column("id", uuid, nullable=False),
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
        sa.ForeignKeyConstraint(
            ["dataset_import_id"], ["dataset_imports.id"], ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("dataset_import_id"),
    )
    op.create_index("ix_import_reports_workspace_id", "import_reports", ["workspace_id"])
    op.create_foreign_key(
        "fk_dataset_versions_import_report_id_import_reports",
        "dataset_versions",
        "import_reports",
        ["import_report_id"],
        ["id"],
        ondelete="SET NULL",
    )


def downgrade() -> None:
    op.drop_constraint(
        "fk_dataset_versions_import_report_id_import_reports",
        "dataset_versions",
        type_="foreignkey",
    )
    op.drop_table("import_reports")
