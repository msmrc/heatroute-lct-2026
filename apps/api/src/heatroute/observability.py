from __future__ import annotations

import json
import logging
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime
from threading import Lock
from time import perf_counter
from typing import Any


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname.lower(),
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key in (
            "event",
            "request_id",
            "method",
            "path",
            "status_code",
            "duration_ms",
            "workspace_id",
            "user_id",
        ):
            value = getattr(record, key, None)
            if value is not None:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_json_logger() -> logging.Logger:
    logger = logging.getLogger("heatroute.http")
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(JsonFormatter())
        logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    return logger


@dataclass
class HttpMetrics:
    requests: Counter[tuple[str, int]] = field(default_factory=Counter)
    duration_seconds: dict[str, float] = field(default_factory=dict)
    _lock: Lock = field(default_factory=Lock)

    def observe(self, method: str, status_code: int, duration_seconds: float) -> None:
        with self._lock:
            self.requests[(method, status_code)] += 1
            self.duration_seconds[method] = (
                self.duration_seconds.get(method, 0.0) + duration_seconds
            )

    def render_prometheus(self) -> str:
        lines = [
            "# HELP heatroute_http_requests_total HTTP requests handled.",
            "# TYPE heatroute_http_requests_total counter",
        ]
        with self._lock:
            for (method, status_code), count in sorted(self.requests.items()):
                lines.append(
                    f'heatroute_http_requests_total{{method="{method}",'
                    f'status="{status_code}"}} {count}'
                )
            lines.extend(
                [
                    "# HELP heatroute_http_request_duration_seconds_sum "
                    "Accumulated HTTP request duration.",
                    "# TYPE heatroute_http_request_duration_seconds_sum counter",
                ]
            )
            for method, duration in sorted(self.duration_seconds.items()):
                lines.append(
                    f'heatroute_http_request_duration_seconds_sum{{method="{method}"}} '
                    f"{duration:.9f}"
                )
        return "\n".join(lines) + "\n"


http_metrics = HttpMetrics()


def request_timer() -> float:
    return perf_counter()
