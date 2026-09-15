import json
import logging

from fastapi.testclient import TestClient
from pytest import MonkeyPatch

from heatroute.api import routes
from heatroute.api.app import _safe_request_id, create_app
from heatroute.api.readiness import ReadinessResult
from heatroute.api.schemas import DependencyStatus
from heatroute.observability import JsonFormatter


def test_liveness_does_not_claim_dependency_readiness() -> None:
    client = TestClient(create_app())
    response = client.get("/api/v1/health/live")
    assert response.status_code == 200
    assert response.json() == {"status": "alive"}
    assert response.headers["x-request-id"]


def test_capabilities_do_not_advertise_unimplemented_features() -> None:
    client = TestClient(create_app())
    response = client.get("/api/v1/capabilities")
    assert response.status_code == 200
    assert response.json() == {
        "schema_version": "1.1",
        "source": "runtime",
        "imports": ["geojson", "gpkg", "csv", "shapefile_zip", "geoparquet"],
        "solvers": ["astar", "dijkstra"],
        "exports": ["geojson", "json", "csv", "html"],
        "demo_seed": True,
        "engineering": {
            "hydraulics": "available",
            "vertical_geometry": "available",
        },
        "construction_methods": [
            "open_trench",
            "horizontal_directional_drilling",
            "microtunneling",
            "pipe_jacking",
            "bridge_attachment",
            "existing_duct",
        ],
    }


def test_readiness_fails_closed_when_dependency_checks_fail(monkeypatch: MonkeyPatch) -> None:
    monkeypatch.setattr(
        routes,
        "run_readiness_checks",
        lambda _settings: ReadinessResult(
            checks={
                "postgis": DependencyStatus(status="error", detail="unavailable"),
                "redis": DependencyStatus(status="error", detail="unavailable"),
            }
        ),
    )
    client = TestClient(create_app())
    response = client.get("/api/v1/health/ready")
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "not_ready"
    assert body["checks"]["postgis"]["status"] == "error"
    assert body["checks"]["redis"]["status"] == "error"


def test_metrics_are_exposed_without_sensitive_request_data() -> None:
    client = TestClient(create_app())
    client.get("/api/v1/capabilities")

    response = client.get("/api/v1/metrics")

    assert response.status_code == 200
    assert "heatroute_http_requests_total" in response.text
    assert "method=\"GET\"" in response.text
    assert "cookie" not in response.text.casefold()


def test_unsafe_request_id_is_replaced() -> None:
    assert _safe_request_id("safe-request-123") == "safe-request-123"
    assert _safe_request_id("x" * 129) != "x" * 129
    assert _safe_request_id("line\nbreak") != "line\nbreak"


def test_structured_logger_uses_an_allowlist_for_context() -> None:
    record = logging.LogRecord(
        name="heatroute.http",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="request_completed",
        args=(),
        exc_info=None,
    )
    record.request_id = "request-1"
    record.method = "POST"
    record.password = "must-not-leak"
    record.cookie = "session=must-not-leak"
    record.body = '{"geometry":"must-not-leak"}'

    payload = json.loads(JsonFormatter().format(record))

    assert payload["request_id"] == "request-1"
    assert payload["method"] == "POST"
    assert "must-not-leak" not in json.dumps(payload)
