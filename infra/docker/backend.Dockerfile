FROM ghcr.io/astral-sh/uv:0.10.3@sha256:7a88d4c4e6f44200575000638453a5a381db0ae31ad5c3a51b14f8687c9d93a3 AS uv
FROM python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PATH="/app/.venv/bin:$PATH" \
    PYTHONPATH="/app/apps/api/src"

COPY --from=uv /uv /uvx /bin/
WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY alembic.ini ./
COPY migrations ./migrations
COPY apps/api/src ./apps/api/src

RUN groupadd --system --gid 10001 heatroute \
    && useradd --system --uid 10001 --gid heatroute --home /app heatroute \
    && mkdir -p /var/lib/heatroute/artifacts \
    && chown heatroute:heatroute /var/lib/heatroute/artifacts

USER heatroute
EXPOSE 8000
CMD ["uvicorn", "heatroute.api.app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
