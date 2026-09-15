from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="HEATROUTE_",
        case_sensitive=False,
        extra="ignore",
    )

    env: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"
    api_host: str = "0.0.0.0"
    api_port: int = Field(default=8000, ge=1, le=65535)
    database_url: str = (
        "postgresql+psycopg://heatroute:heatroute_dev_only@localhost:55432/heatroute"
    )
    redis_url: str = "redis://localhost:56379/0"
    celery_broker_url: str = "redis://localhost:56379/1"
    celery_result_backend: str = "redis://localhost:56379/2"
    artifact_root: Path = Path("artifacts")
    max_upload_bytes: int = Field(default=100 * 1024 * 1024, ge=1, le=2 * 1024 * 1024 * 1024)
    demo_mode: bool = True
    session_secret: SecretStr = SecretStr("development-only-change-me")
    session_ttl_hours: int = Field(default=12, ge=1, le=24 * 30)
    cors_origins: list[str] = ["http://localhost:5173"]
    max_queued_route_jobs: int = Field(default=100, ge=1, le=100_000)
    route_job_lease_seconds: int = Field(default=300, ge=30, le=3600)

    @model_validator(mode="after")
    def reject_unsafe_production_defaults(self) -> "Settings":
        if self.env == "production":
            if self.demo_mode:
                raise ValueError("HEATROUTE_DEMO_MODE must be false in production")
            if self.session_secret.get_secret_value() == "development-only-change-me":
                raise ValueError("HEATROUTE_SESSION_SECRET must be replaced in production")
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
