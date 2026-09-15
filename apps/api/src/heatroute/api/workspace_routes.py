from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from heatroute.api.schemas import (
    ManualRouteValidationRequest,
    ManualRouteValidationResponse,
    PreflightFinding,
    PreflightResponse,
    ProjectCreate,
    ProjectResponse,
    ProjectUpdate,
    RunAcceptedResponse,
    RunCreateRequest,
    ScenarioCreate,
    ScenarioResponse,
    ScenarioRevisionCreate,
    ScenarioRevisionResponse,
)
from heatroute.db import get_db_session
from heatroute.models import Project, Scenario, ScenarioRevision, Workspace
from heatroute.services.auth import Principal, require_editor, require_reader
from heatroute.services.demo_runs import DEMO_WORKSPACE_ID
from heatroute.services.manual_routes import validate_manual_route
from heatroute.services.runs import (
    IdempotencyConflictError,
    PreflightNotReadyError,
    QueueOverloadedError,
    canonical_hash,
    create_revision_run,
    evaluate_revision_preflight,
)

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
IdempotencyKey = Annotated[
    str, Header(alias="Idempotency-Key", min_length=1, max_length=128)
]
IfMatch = Annotated[str | None, Header(alias="If-Match")]


def _ensure_workspace(session: Session, workspace_id: UUID) -> Workspace:
    workspace = session.get(Workspace, workspace_id)
    if workspace is None and workspace_id == DEMO_WORKSPACE_ID:
        workspace = Workspace(id=workspace_id, name="HeatRoute demo")
        session.add(workspace)
        session.flush()
    if workspace is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="workspace not found")
    return workspace


def _project_response(project: Project) -> ProjectResponse:
    return ProjectResponse(
        id=project.id,
        workspace_id=project.workspace_id,
        name=project.name,
        description=project.description,
        working_crs=project.working_crs,
        crs_confirmed=project.crs_confirmed,
        source_mode=project.source_mode,
        current_revision=project.current_revision,
        created_at=project.created_at,
        updated_at=project.updated_at,
    )


def _revision_response(revision: ScenarioRevision) -> ScenarioRevisionResponse:
    return ScenarioRevisionResponse(
        id=revision.id,
        scenario_id=revision.scenario_id,
        revision=revision.revision,
        input_hash=revision.input_hash,
        input_snapshot=revision.input_snapshot,
        created_at=revision.created_at,
    )


def _get_project(session: Session, project_id: UUID, workspace_id: UUID) -> Project:
    project = session.scalar(
        select(Project).where(
            Project.id == project_id,
            Project.workspace_id == workspace_id,
        )
    )
    if project is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="project not found")
    return project


def _get_scenario(
    session: Session, scenario_id: UUID, workspace_id: UUID, *, lock: bool = False
) -> Scenario:
    query = select(Scenario).where(
        Scenario.id == scenario_id,
        Scenario.workspace_id == workspace_id,
    )
    if lock:
        query = query.with_for_update()
    scenario = session.scalar(query)
    if scenario is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="scenario not found")
    return scenario


def _get_revision(session: Session, revision_id: UUID, workspace_id: UUID) -> ScenarioRevision:
    revision = session.scalar(
        select(ScenarioRevision)
        .join(Scenario, Scenario.id == ScenarioRevision.scenario_id)
        .where(
            ScenarioRevision.id == revision_id,
            Scenario.workspace_id == workspace_id,
        )
    )
    if revision is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="scenario revision not found",
        )
    return revision


def _parse_revision_precondition(if_match: str | None) -> int:
    if if_match is None:
        raise HTTPException(
            status_code=status.HTTP_428_PRECONDITION_REQUIRED,
            detail="If-Match revision is required",
        )
    normalized = if_match.strip()
    if normalized.startswith("W/"):
        normalized = normalized[2:]
    normalized = normalized.strip('"')
    try:
        return int(normalized)
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="If-Match must contain an integer revision",
        ) from error


@router.get("/projects", response_model=list[ProjectResponse], tags=["projects"])
def list_projects(session: DbSession, principal: Reader) -> list[ProjectResponse]:
    projects = session.scalars(
        select(Project)
        .where(Project.workspace_id == principal.workspace_id)
        .order_by(Project.created_at, Project.id)
    ).all()
    return [_project_response(project) for project in projects]


@router.post(
    "/projects",
    response_model=ProjectResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["projects"],
)
def create_project(
    request: ProjectCreate, session: DbSession, principal: Editor
) -> ProjectResponse:
    workspace = _ensure_workspace(session, principal.workspace_id)
    project = Project(
        workspace_id=workspace.id,
        name=request.name,
        description=request.description,
        working_crs=request.working_crs,
        crs_confirmed=request.crs_confirmed,
        source_mode=request.source_mode,
        current_revision=1,
    )
    session.add(project)
    session.commit()
    session.refresh(project)
    return _project_response(project)


@router.get("/projects/{project_id}", response_model=ProjectResponse, tags=["projects"])
def get_project(project_id: UUID, session: DbSession, principal: Reader) -> ProjectResponse:
    return _project_response(_get_project(session, project_id, principal.workspace_id))


@router.patch("/projects/{project_id}", response_model=ProjectResponse, tags=["projects"])
def update_project(
    project_id: UUID,
    request: ProjectUpdate,
    session: DbSession,
    principal: Editor,
    if_match: IfMatch = None,
) -> ProjectResponse:
    expected_revision = _parse_revision_precondition(if_match)
    project = session.scalar(
        select(Project)
        .where(Project.id == project_id, Project.workspace_id == principal.workspace_id)
        .with_for_update()
    )
    if project is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="project not found")
    if project.current_revision != expected_revision:
        raise HTTPException(
            status_code=status.HTTP_412_PRECONDITION_FAILED,
            detail={
                "code": "STALE_PROJECT_REVISION",
                "expected": project.current_revision,
                "received": expected_revision,
            },
        )
    changed = request.model_dump(exclude_unset=True)
    if "working_crs" in changed and "crs_confirmed" not in changed:
        changed["crs_confirmed"] = False
    for field, value in changed.items():
        setattr(project, field, value)
    project.current_revision += 1
    session.commit()
    session.refresh(project)
    return _project_response(project)


@router.post(
    "/projects/{project_id}/scenarios",
    response_model=ScenarioResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["scenarios"],
)
def create_scenario(
    project_id: UUID,
    request: ScenarioCreate,
    session: DbSession,
    principal: Editor,
) -> ScenarioResponse:
    project = _get_project(session, project_id, principal.workspace_id)
    scenario = Scenario(
        workspace_id=project.workspace_id,
        project_id=project.id,
        name=request.name,
    )
    session.add(scenario)
    session.commit()
    session.refresh(scenario)
    return ScenarioResponse(
        id=scenario.id,
        workspace_id=scenario.workspace_id,
        project_id=scenario.project_id,
        name=scenario.name,
        created_at=scenario.created_at,
        updated_at=scenario.updated_at,
        revisions=[],
    )


@router.get(
    "/projects/{project_id}/scenarios",
    response_model=list[ScenarioResponse],
    tags=["scenarios"],
)
def list_project_scenarios(
    project_id: UUID, session: DbSession, principal: Reader
) -> list[ScenarioResponse]:
    project = _get_project(session, project_id, principal.workspace_id)
    scenarios = session.scalars(
        select(Scenario)
        .where(
            Scenario.project_id == project.id,
            Scenario.workspace_id == project.workspace_id,
        )
        .order_by(Scenario.updated_at.desc(), Scenario.id)
    ).all()
    responses: list[ScenarioResponse] = []
    for scenario in scenarios:
        revisions = session.scalars(
            select(ScenarioRevision)
            .where(ScenarioRevision.scenario_id == scenario.id)
            .order_by(ScenarioRevision.revision)
        ).all()
        responses.append(
            ScenarioResponse(
                id=scenario.id,
                workspace_id=scenario.workspace_id,
                project_id=scenario.project_id,
                name=scenario.name,
                created_at=scenario.created_at,
                updated_at=scenario.updated_at,
                revisions=[_revision_response(revision) for revision in revisions],
            )
        )
    return responses


@router.get("/scenarios/{scenario_id}", response_model=ScenarioResponse, tags=["scenarios"])
def get_scenario(
    scenario_id: UUID, session: DbSession, principal: Reader
) -> ScenarioResponse:
    scenario = _get_scenario(session, scenario_id, principal.workspace_id)
    revisions = session.scalars(
        select(ScenarioRevision)
        .where(ScenarioRevision.scenario_id == scenario.id)
        .order_by(ScenarioRevision.revision)
    ).all()
    return ScenarioResponse(
        id=scenario.id,
        workspace_id=scenario.workspace_id,
        project_id=scenario.project_id,
        name=scenario.name,
        created_at=scenario.created_at,
        updated_at=scenario.updated_at,
        revisions=[_revision_response(revision) for revision in revisions],
    )


@router.post(
    "/scenarios/{scenario_id}/revisions",
    response_model=ScenarioRevisionResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["scenarios"],
)
def create_scenario_revision(
    scenario_id: UUID,
    request: ScenarioRevisionCreate,
    session: DbSession,
    principal: Editor,
    if_match: IfMatch = None,
) -> ScenarioRevisionResponse:
    expected_revision = _parse_revision_precondition(if_match)
    scenario = _get_scenario(session, scenario_id, principal.workspace_id, lock=True)
    latest_revision = session.scalar(
        select(func.coalesce(func.max(ScenarioRevision.revision), 0)).where(
            ScenarioRevision.scenario_id == scenario.id
        )
    )
    assert latest_revision is not None
    if latest_revision != expected_revision:
        raise HTTPException(
            status_code=status.HTTP_412_PRECONDITION_FAILED,
            detail={
                "code": "STALE_SCENARIO_REVISION",
                "expected": latest_revision,
                "received": expected_revision,
            },
        )
    snapshot = request.model_dump(mode="json")
    revision = ScenarioRevision(
        scenario_id=scenario.id,
        revision=latest_revision + 1,
        input_hash=canonical_hash(snapshot),
        input_snapshot=snapshot,
    )
    session.add(revision)
    session.commit()
    session.refresh(revision)
    return _revision_response(revision)


@router.post(
    "/scenario-revisions/{revision_id}/preflight",
    response_model=PreflightResponse,
    tags=["scenarios", "runs"],
)
def preflight_scenario_revision(
    revision_id: UUID,
    session: DbSession,
    principal: Reader,
) -> PreflightResponse:
    revision = _get_revision(session, revision_id, principal.workspace_id)
    result = evaluate_revision_preflight(session, revision)
    return PreflightResponse(
        scenario_revision_id=revision.id,
        ready=result.ready,
        findings=[PreflightFinding.model_validate(finding) for finding in result.findings],
    )


@router.post(
    "/scenario-revisions/{revision_id}/validate-route",
    response_model=ManualRouteValidationResponse,
    tags=["scenarios", "runs"],
)
def validate_scenario_revision_route(
    revision_id: UUID,
    request: ManualRouteValidationRequest,
    session: DbSession,
    principal: Reader,
) -> ManualRouteValidationResponse:
    revision = _get_revision(session, revision_id, principal.workspace_id)
    try:
        result = validate_manual_route(
            session,
            revision=revision,
            centerline_wgs84=request.centerline_wgs84,
        )
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "MANUAL_ROUTE_INVALID", "message": str(error)},
        ) from error
    return ManualRouteValidationResponse.model_validate(result)


@router.post(
    "/scenario-revisions/{revision_id}/runs",
    response_model=RunAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["runs"],
)
def start_revision_run(
    revision_id: UUID,
    request: RunCreateRequest,
    idempotency_key: IdempotencyKey,
    session: DbSession,
    principal: Editor,
) -> RunAcceptedResponse:
    revision = _get_revision(session, revision_id, principal.workspace_id)
    try:
        run = create_revision_run(
            session,
            revision=revision,
            algorithm=request.algorithm,
            idempotency_key=idempotency_key,
        )
    except IdempotencyConflictError as error:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error
    except PreflightNotReadyError as error:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "PREFLIGHT_FAILED", "findings": error.findings},
        ) from error
    except QueueOverloadedError as error:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail={"code": "QUEUE_OVERLOADED", "message": str(error)},
            headers={"Retry-After": "5"},
        ) from error
    return RunAcceptedResponse(
        run_id=run.id,
        job_id=run.job_id,
        job_state=run.job_state,
        status_url=f"/api/v1/runs/{run.id}",
    )
