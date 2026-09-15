"""Repair timestamp defaults for early 0008 deployments.

Revision ID: 20260908_0009
Revises: 20260908_0008
Create Date: 2026-09-08
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260908_0009"
down_revision: str | None = "20260908_0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column(
        "dataset_layer_versions", "created_at", server_default=sa.text("now()")
    )
    op.alter_column(
        "dataset_layer_versions", "updated_at", server_default=sa.text("now()")
    )


def downgrade() -> None:
    op.alter_column("dataset_layer_versions", "updated_at", server_default=None)
    op.alter_column("dataset_layer_versions", "created_at", server_default=None)
