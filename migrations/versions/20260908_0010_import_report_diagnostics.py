"""Add structured import report diagnostics.

Revision ID: 20260908_0010
Revises: 20260908_0009
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0010"
down_revision: str | None = "20260908_0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    jsonb = postgresql.JSONB(astext_type=sa.Text())
    op.add_column(
        "import_reports",
        sa.Column(
            "missing_value_distribution",
            jsonb,
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )
    for column_name in (
        "source_references",
        "repairs",
        "crs_diagnostics",
        "topology_diagnostics",
        "coverage_diagnostics",
    ):
        op.add_column(
            "import_reports",
            sa.Column(
                column_name,
                jsonb,
                server_default=sa.text("'[]'::jsonb"),
                nullable=False,
            ),
        )


def downgrade() -> None:
    for column_name in reversed(
        (
            "source_references",
            "repairs",
            "crs_diagnostics",
            "topology_diagnostics",
            "coverage_diagnostics",
        )
    ):
        op.drop_column("import_reports", column_name)
    op.drop_column("import_reports", "missing_value_distribution")
