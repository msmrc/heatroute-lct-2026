import pytest
from fastapi.testclient import TestClient

from heatroute.api.app import create_app
from heatroute.config import Settings, get_settings
from heatroute.services.auth import hash_password, verify_password


def test_scrypt_password_hash_is_salted_and_verifiable() -> None:
    first = hash_password("correct-horse-battery-staple")
    second = hash_password("correct-horse-battery-staple")

    assert first != second
    assert verify_password("correct-horse-battery-staple", first)
    assert not verify_password("wrong-password", first)
    assert not verify_password("correct-horse-battery-staple", "not-a-password-hash")


def test_non_demo_api_requires_authentication() -> None:
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: Settings(env="test", demo_mode=False)

    response = TestClient(app).get("/api/v1/projects")

    assert response.status_code == 401


def test_production_rejects_demo_mode_and_default_session_secret() -> None:
    with pytest.raises(ValueError, match="DEMO_MODE"):
        Settings(env="production", demo_mode=True, session_secret="a-secure-production-secret")
    with pytest.raises(ValueError, match="SESSION_SECRET"):
        Settings(env="production", demo_mode=False)
