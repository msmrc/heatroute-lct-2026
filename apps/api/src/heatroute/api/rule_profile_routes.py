from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.api.schemas import (
    RuleProfileCreate,
    RuleProfileResponse,
    RuleProfileVersionCreate,
    RuleProfileVersionResponse,
)
from heatroute.db import get_db_session
from heatroute.models import Project
from heatroute.services.auth import Principal, require_editor, require_reader
from heatroute.services.rule_profiles import (
    RuleProfileRecord,
    RuleProfileValidationError,
    StaleRuleProfileRevisionError,
    create_rule_profile,
    get_rule_profile,
    list_rule_profiles,
    revise_rule_profile,
)

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
IfMatch = Annotated[str | None, Header(alias="If-Match")]


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


def _response(record: RuleProfileRecord) -> RuleProfileResponse:
    profile = record.profile
    return RuleProfileResponse(
        id=profile.id,
        workspace_id=profile.workspace_id,
        project_id=profile.project_id,
        name=profile.name,
        current_revision=profile.current_revision,
        created_at=profile.created_at,
        updated_at=profile.updated_at,
        versions=[
            RuleProfileVersionResponse(
                id=version.id,
                profile_id=version.profile_id,
                revision=version.revision,
                definition_hash=version.definition_hash,
                status=version.status,
                definition=version.definition,
                created_at=version.created_at,
            )
            for version in record.versions
        ],
    )


def _project(session: Session, project_id: UUID, workspace_id: UUID) -> Project:
    project = session.scalar(
        select(Project).where(
            Project.id == project_id,
            Project.workspace_id == workspace_id,
        )
    )
    if project is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="project not found")
    return project


@router.get(
    "/projects/{project_id}/rule-profiles",
    response_model=list[RuleProfileResponse],
    tags=["rule-profiles"],
)
def list_project_rule_profiles(
    project_id: UUID, session: DbSession, principal: Reader
) -> list[RuleProfileResponse]:
    _project(session, project_id, principal.workspace_id)
    return [
        _response(record)
        for record in list_rule_profiles(
            session,
            project_id=project_id,
            workspace_id=principal.workspace_id,
        )
    ]


@router.post(
    "/projects/{project_id}/rule-profiles",
    response_model=RuleProfileResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["rule-profiles"],
)
def create_project_rule_profile(
    project_id: UUID,
    request: RuleProfileCreate,
    session: DbSession,
    principal: Editor,
) -> RuleProfileResponse:
    project = _project(session, project_id, principal.workspace_id)
    try:
        record = create_rule_profile(
            session,
            project=project,
            name=request.name,
            definition=request.definition,
        )
    except RuleProfileValidationError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail={"code": "INVALID_RULE_PROFILE", "message": str(error)},
        ) from error
    return _response(record)


@router.get(
    "/rule-profiles/{profile_id}",
    response_model=RuleProfileResponse,
    tags=["rule-profiles"],
)
def read_rule_profile(
    profile_id: UUID, session: DbSession, principal: Reader
) -> RuleProfileResponse:
    record = get_rule_profile(
        session,
        profile_id=profile_id,
        workspace_id=principal.workspace_id,
    )
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="rule profile not found",
        )
    return _response(record)


@router.post(
    "/rule-profiles/{profile_id}/versions",
    response_model=RuleProfileResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["rule-profiles"],
)
def create_rule_profile_version(
    profile_id: UUID,
    request: RuleProfileVersionCreate,
    session: DbSession,
    principal: Editor,
    if_match: IfMatch = None,
) -> RuleProfileResponse:
    expected_revision = _parse_revision_precondition(if_match)
    try:
        record = revise_rule_profile(
            session,
            profile_id=profile_id,
            workspace_id=principal.workspace_id,
            expected_revision=expected_revision,
            definition=request.definition,
        )
    except StaleRuleProfileRevisionError as error:
        raise HTTPException(
            status_code=status.HTTP_412_PRECONDITION_FAILED,
            detail={
                "code": "STALE_RULE_PROFILE_REVISION",
                "expected": error.expected,
                "received": error.received,
            },
        ) from error
    except RuleProfileValidationError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail={"code": "INVALID_RULE_PROFILE", "message": str(error)},
        ) from error
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="rule profile not found",
        )
    return _response(record)
