.PHONY: bootstrap up down migrate seed-demo test test-integration test-e2e benchmark-demo lint typecheck export-openapi worker-smoke security-audit

bootstrap:
	uv sync --frozen
	pnpm install --frozen-lockfile

up:
	docker compose up --build -d

down:
	docker compose down

migrate:
	docker compose run --rm migrate

seed-demo:
	docker compose exec -T api python -m heatroute seed-demo --wait 30

test:
	uv run pytest -m "not integration"
	pnpm test

test-integration:
	uv run pytest -m integration

test-e2e:
	uv run pytest -m integration tests/integration/test_demo_run.py tests/integration/test_m4_workspace_api.py

benchmark-demo:
	uv run python scripts/benchmark_m3.py

lint:
	uv run ruff check apps/api/src tests
	pnpm lint

typecheck:
	uv run mypy apps/api/src
	pnpm typecheck

export-openapi:
	uv run python -m heatroute export-openapi

worker-smoke:
	uv run python -m heatroute worker-smoke

security-audit:
	uv run python scripts/security_audit.py
