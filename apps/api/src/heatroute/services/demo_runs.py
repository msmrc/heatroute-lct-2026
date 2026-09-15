from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.models import (
    CalculationRun,
    Project,
    Scenario,
    ScenarioRevision,
    Workspace,
)
from heatroute.services.runs import (
    IdempotencyConflictError,
    canonical_hash,
    create_revision_run,
)

DEMO_WORKSPACE_ID = UUID("00000000-0000-4000-8000-000000000001")


def create_demo_run(
    session: Session,
    *,
    snapshot: dict[str, Any],
    algorithm: str,
    idempotency_key: str,
) -> CalculationRun:
    request_hash = canonical_hash({"snapshot": snapshot, "algorithm": algorithm})
    existing = session.scalar(
        select(CalculationRun).where(
            CalculationRun.workspace_id == DEMO_WORKSPACE_ID,
            CalculationRun.idempotency_key == idempotency_key,
        )
    )
    if existing is not None:
        if existing.input_hash != request_hash:
            raise IdempotencyConflictError("idempotency key was used for a different request")
        return existing

    workspace = session.get(Workspace, DEMO_WORKSPACE_ID)
    if workspace is None:
        workspace = Workspace(id=DEMO_WORKSPACE_ID, name="HeatRoute demo")
        session.add(workspace)
        session.flush()
    project = Project(
        workspace_id=workspace.id,
        name="Синтетический район",
        description="Автоматически созданный point-to-point demo",
        working_crs="EPSG:32637",
        crs_confirmed=True,
        source_mode="synthetic",
    )
    session.add(project)
    session.flush()
    scenario = Scenario(
        workspace_id=workspace.id,
        project_id=project.id,
        name="Обход препятствия",
    )
    session.add(scenario)
    session.flush()
    revision = ScenarioRevision(
        scenario_id=scenario.id,
        revision=1,
        input_hash=canonical_hash(snapshot),
        input_snapshot=snapshot,
    )
    session.add(revision)
    session.flush()
    return create_revision_run(
        session,
        revision=revision,
        algorithm=algorithm,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
    )
