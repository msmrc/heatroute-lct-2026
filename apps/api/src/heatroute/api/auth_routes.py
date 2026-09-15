from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.api.schemas import (
    AuthSessionResponse,
    LoginRequest,
    UserCreateRequest,
    UserResponse,
)
from heatroute.config import Settings, get_settings
from heatroute.db import get_db_session
from heatroute.models import AuthSession, User, Workspace
from heatroute.services.auth import (
    SESSION_COOKIE,
    Principal,
    create_auth_session,
    hash_password,
    require_admin,
    require_reader,
    verify_password,
)
from heatroute.services.demo_runs import DEMO_WORKSPACE_ID

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
AppSettings = Annotated[Settings, Depends(get_settings)]
Reader = Annotated[Principal, Depends(require_reader)]
Admin = Annotated[Principal, Depends(require_admin)]


def _user_response(user: User) -> UserResponse:
    return UserResponse(
        id=user.id,
        workspace_id=user.workspace_id,
        email=user.email,
        role=user.role,
        is_active=user.is_active,
    )


@router.post(
    "/auth/users",
    response_model=UserResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["auth"],
)
def create_user(request: UserCreateRequest, principal: Admin, session: DbSession) -> UserResponse:
    workspace = session.get(Workspace, principal.workspace_id)
    if workspace is None and principal.workspace_id == DEMO_WORKSPACE_ID:
        workspace = Workspace(id=DEMO_WORKSPACE_ID, name="HeatRoute demo")
        session.add(workspace)
        session.flush()
    if workspace is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="workspace not found")
    email = request.email.strip().casefold()
    if session.scalar(
        select(User).where(User.workspace_id == workspace.id, User.email == email)
    ) is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="user already exists")
    user = User(
        workspace_id=workspace.id,
        email=email,
        password_hash=hash_password(request.password),
        role=request.role,
        is_active=True,
    )
    session.add(user)
    session.commit()
    session.refresh(user)
    return _user_response(user)


@router.post("/auth/login", response_model=AuthSessionResponse, tags=["auth"])
def login(
    request: LoginRequest,
    response: Response,
    session: DbSession,
    settings: AppSettings,
) -> AuthSessionResponse:
    workspace_id = request.workspace_id
    if workspace_id is None and settings.demo_mode and settings.env != "production":
        workspace_id = DEMO_WORKSPACE_ID
    if workspace_id is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="workspace_id is required",
        )
    user = session.scalar(
        select(User).where(
            User.workspace_id == workspace_id,
            User.email == request.email.strip().casefold(),
        )
    )
    if (
        user is None
        or not user.is_active
        or not verify_password(request.password, user.password_hash)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid credentials"
        )
    created = create_auth_session(session, user=user, settings=settings)
    session.commit()
    session.refresh(created.row)
    response.set_cookie(
        SESSION_COOKIE,
        created.token,
        max_age=settings.session_ttl_hours * 3600,
        httponly=True,
        secure=settings.env == "production",
        samesite="lax",
        path="/",
    )
    return AuthSessionResponse(
        user=_user_response(user),
        csrf_token=created.csrf_token,
        expires_at=created.row.expires_at,
    )


@router.get("/auth/me", response_model=UserResponse, tags=["auth"])
def me(principal: Reader) -> UserResponse:
    if principal.user_id is None or principal.email is None:
        return UserResponse(
            id=DEMO_WORKSPACE_ID,
            workspace_id=principal.workspace_id,
            email="demo@localhost",
            role=principal.role,
            is_active=True,
        )
    return UserResponse(
        id=principal.user_id,
        workspace_id=principal.workspace_id,
        email=principal.email,
        role=principal.role,
        is_active=True,
    )


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT, tags=["auth"])
def logout(principal: Reader, response: Response, session: DbSession) -> None:
    if principal.auth_session_id is not None:
        row = session.get(AuthSession, principal.auth_session_id)
        if row is not None:
            from datetime import UTC, datetime

            row.revoked_at = datetime.now(UTC)
            session.commit()
    response.delete_cookie(SESSION_COOKIE, path="/")
