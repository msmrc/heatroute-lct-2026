from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Annotated
from uuid import UUID

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.config import Settings, get_settings
from heatroute.db import get_db_session
from heatroute.models import AuthSession, User
from heatroute.services.demo_runs import DEMO_WORKSPACE_ID

SESSION_COOKIE = "heatroute_session"
SCRYPT_N = 2**14
SCRYPT_R = 8
SCRYPT_P = 1


@dataclass(frozen=True)
class Principal:
    workspace_id: UUID
    user_id: UUID | None
    role: str
    email: str | None
    auth_session_id: UUID | None


@dataclass(frozen=True)
class CreatedSession:
    row: AuthSession
    token: str
    csrf_token: str


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P
    )
    return "$".join(
        (
            "scrypt",
            str(SCRYPT_N),
            str(SCRYPT_R),
            str(SCRYPT_P),
            base64.urlsafe_b64encode(salt).decode("ascii"),
            base64.urlsafe_b64encode(digest).decode("ascii"),
        )
    )


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, raw_n, raw_r, raw_p, raw_salt, raw_digest = encoded.split("$")
        if algorithm != "scrypt":
            return False
        salt = base64.urlsafe_b64decode(raw_salt.encode("ascii"))
        expected = base64.urlsafe_b64decode(raw_digest.encode("ascii"))
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=int(raw_n),
            r=int(raw_r),
            p=int(raw_p),
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(actual, expected)


def create_auth_session(
    session: Session, *, user: User, settings: Settings
) -> CreatedSession:
    now = datetime.now(UTC)
    token = secrets.token_urlsafe(32)
    csrf_token = secrets.token_urlsafe(32)
    row = AuthSession(
        workspace_id=user.workspace_id,
        user_id=user.id,
        token_hash=_sha256(token),
        csrf_hash=_sha256(csrf_token),
        expires_at=now + timedelta(hours=settings.session_ttl_hours),
        last_seen_at=now,
    )
    session.add(row)
    session.flush()
    return CreatedSession(row=row, token=token, csrf_token=csrf_token)


def authenticate_request(
    request: Request,
    session: Annotated[Session, Depends(get_db_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Principal:
    token = request.cookies.get(SESSION_COOKIE)
    if token is None:
        if settings.demo_mode and settings.env != "production":
            principal = Principal(DEMO_WORKSPACE_ID, None, "admin", None, None)
            request.state.principal = principal
            return principal
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="authentication required",
        )
    row = session.execute(
        select(AuthSession, User)
        .join(User, User.id == AuthSession.user_id)
        .where(AuthSession.token_hash == _sha256(token))
    ).one_or_none()
    now = datetime.now(UTC)
    if row is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid session")
    auth_session, user = row
    if auth_session.revoked_at is not None or auth_session.expires_at <= now or not user.is_active:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="expired session")
    if auth_session.workspace_id != user.workspace_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid session")
    if request.method not in {"GET", "HEAD", "OPTIONS"}:
        csrf_token = request.headers.get("X-CSRF-Token")
        if csrf_token is None or not hmac.compare_digest(
            _sha256(csrf_token), auth_session.csrf_hash
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="valid X-CSRF-Token header required",
            )
    principal = Principal(
        workspace_id=user.workspace_id,
        user_id=user.id,
        role=user.role,
        email=user.email,
        auth_session_id=auth_session.id,
    )
    request.state.principal = principal
    return principal


def require_reader(
    principal: Annotated[Principal, Depends(authenticate_request)],
) -> Principal:
    return principal


def require_editor(
    principal: Annotated[Principal, Depends(authenticate_request)],
) -> Principal:
    if principal.role not in {"editor", "admin"}:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="editor role required")
    return principal


def require_admin(
    principal: Annotated[Principal, Depends(authenticate_request)],
) -> Principal:
    if principal.role != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="admin role required")
    return principal
