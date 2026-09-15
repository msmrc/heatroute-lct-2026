"""Add immutable calculation input versions snapshot.

Revision ID: 20260908_0013
Revises: 20260908_0012
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260908_0013"
down_revision: str | None = "20260908_0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "calculation_runs",
        sa.Column(
            "versions_snapshot",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )
    op.alter_column("calculation_runs", "versions_snapshot", server_default=None)


def downgrade() -> None:
    op.drop_column("calculation_runs", "versions_snapshot")
