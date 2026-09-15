from collections.abc import Awaitable, Callable
from time import perf_counter
from uuid import uuid4

from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware

from heatroute import __version__
from heatroute.api.auth_routes import router as auth_router
from heatroute.api.cost_catalog_routes import router as cost_catalog_router
from heatroute.api.engineering_routes import router as engineering_router
from heatroute.api.ingestion_routes import router as ingestion_router
from heatroute.api.routes import router
from heatroute.api.rule_profile_routes import router as rule_profile_router
from heatroute.api.workspace_routes import router as workspace_router
from heatroute.config import get_settings
from heatroute.db import SessionLocal
from heatroute.models import AuditEvent
from heatroute.observability import configure_json_logger, http_metrics


def _safe_request_id(value: str | None) -> str:
    if value and len(value) <= 128 and all(32 <= ord(character) < 127 for character in value):
        return value
    return str(uuid4())


def create_app() -> FastAPI:
    settings = get_settings()
    http_logger = configure_json_logger()
    app = FastAPI(
        title="HeatRoute API",
        version=__version__,
        description="Preliminary district-heating route planning backend",
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
        allow_headers=["Content-Type", "Idempotency-Key", "If-Match", "X-CSRF-Token"],
    )

    @app.middleware("http")
    async def request_id_middleware(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        request_id = _safe_request_id(request.headers.get("X-Request-ID"))
        request.state.request_id = request_id
        started = perf_counter()
        response = await call_next(request)
        elapsed = perf_counter() - started
        response.headers["X-Request-ID"] = request_id
        http_metrics.observe(request.method, response.status_code, elapsed)
        principal = getattr(request.state, "principal", None)
        log_fields = {
            "event": "http.request",
            "request_id": request_id,
            "method": request.method,
            "path": request.url.path,
            "status_code": response.status_code,
            "duration_ms": round(elapsed * 1000, 3),
            "workspace_id": str(principal.workspace_id) if principal else None,
            "user_id": str(principal.user_id) if principal and principal.user_id else None,
        }
        http_logger.info("request completed", extra=log_fields)
        if principal is not None and request.method in {"POST", "PUT", "PATCH", "DELETE"}:
            try:
                with SessionLocal() as session:
                    session.add(
                        AuditEvent(
                            workspace_id=principal.workspace_id,
                            actor_user_id=principal.user_id,
                            request_id=request_id[:128],
                            method=request.method,
                            path=request.url.path[:500],
                            status_code=response.status_code,
                            action="mutation",
                            metadata_json={"content_type": response.headers.get("content-type")},
                        )
                    )
                    session.commit()
            except Exception:
                http_logger.exception(
                    "audit event persistence failed",
                    extra={"event": "audit.persist_failed", "request_id": request_id},
                )
        return response

    app.include_router(router, prefix="/api/v1")
    app.include_router(auth_router, prefix="/api/v1")
    app.include_router(workspace_router, prefix="/api/v1")
    app.include_router(ingestion_router, prefix="/api/v1")
    app.include_router(rule_profile_router, prefix="/api/v1")
    app.include_router(cost_catalog_router, prefix="/api/v1")
    app.include_router(engineering_router, prefix="/api/v1")
    return app


app = create_app()
