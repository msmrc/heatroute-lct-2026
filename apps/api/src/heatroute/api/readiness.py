from dataclasses import dataclass

from redis import Redis
from sqlalchemy import create_engine, text

from heatroute.api.schemas import DependencyStatus
from heatroute.config import Settings


@dataclass(frozen=True)
class ReadinessResult:
    checks: dict[str, DependencyStatus]

    @property
    def ready(self) -> bool:
        return all(check.status == "ok" for check in self.checks.values())


def run_readiness_checks(settings: Settings) -> ReadinessResult:
    checks: dict[str, DependencyStatus] = {}

    engine = create_engine(settings.database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            postgis_version = connection.execute(text("SELECT PostGIS_Version()")).scalar_one()
        checks["postgis"] = DependencyStatus(status="ok", detail=str(postgis_version))
    except Exception:
        checks["postgis"] = DependencyStatus(status="error", detail="unavailable")
    finally:
        engine.dispose()

    redis_client = Redis.from_url(settings.redis_url, socket_connect_timeout=1, socket_timeout=1)
    try:
        if redis_client.ping():
            checks["redis"] = DependencyStatus(status="ok")
        else:
            checks["redis"] = DependencyStatus(status="error", detail="unexpected response")
    except Exception:
        checks["redis"] = DependencyStatus(status="error", detail="unavailable")
    finally:
        redis_client.close()

    return ReadinessResult(checks=checks)
