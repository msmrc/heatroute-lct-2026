from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app
from heatroute.db import SessionLocal
from heatroute.models import Project, User, Workspace
from heatroute.services.auth import hash_password


@pytest.mark.integration
def test_viewer_is_read_only_and_session_mutations_require_csrf() -> None:
    admin = TestClient(create_app())
    email = f"viewer-{uuid4()}@example.test"
    password = "correct-horse-battery-staple"
    created = admin.post(
        "/api/v1/auth/users",
        json={"email": email, "password": password, "role": "viewer"},
    )
    assert created.status_code == 201, created.text

    viewer = TestClient(create_app())
    login = viewer.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert login.status_code == 200, login.text
    csrf_token = login.json()["csrf_token"]
    assert viewer.get("/api/v1/projects").status_code == 200
    without_csrf = viewer.post("/api/v1/projects", json={"name": "Forbidden"})
    assert without_csrf.status_code == 403
    forbidden = viewer.post(
        "/api/v1/projects",
        json={"name": "Still forbidden"},
        headers={"X-CSRF-Token": csrf_token},
    )
    assert forbidden.status_code == 403
    publish = viewer.post(
        f"/api/v1/imports/{uuid4()}/publish",
        json={},
        headers={"X-CSRF-Token": csrf_token},
    )
    assert publish.status_code == 403


@pytest.mark.integration
def test_session_workspace_boundary_hides_other_projects() -> None:
    workspace_id = uuid4()
    project_id = uuid4()
    email = f"isolated-{uuid4()}@example.test"
    password = "another-correct-password"
    with SessionLocal() as session:
        session.add(Workspace(id=workspace_id, name="Isolated workspace"))
        session.flush()
        session.add(
            User(
                workspace_id=workspace_id,
                email=email,
                password_hash=hash_password(password),
                role="editor",
                is_active=True,
            )
        )
        session.add(
            Project(
                id=project_id,
                workspace_id=workspace_id,
                name="Private project",
                source_mode="provided",
                current_revision=1,
            )
        )
        session.commit()

    isolated = TestClient(create_app())
    login = isolated.post(
        "/api/v1/auth/login",
        json={"workspace_id": str(workspace_id), "email": email, "password": password},
    )
    assert login.status_code == 200, login.text
    assert isolated.get(f"/api/v1/projects/{project_id}").status_code == 200

    demo = TestClient(create_app())
    assert demo.get(f"/api/v1/projects/{project_id}").status_code == 404
