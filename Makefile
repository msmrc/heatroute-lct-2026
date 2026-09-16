.PHONY: bootstrap up down test backend-test web-test lint typecheck build logs

bootstrap:
	pnpm install --frozen-lockfile

up:
	docker compose up --build -d --wait

down:
	docker compose down

backend-test:
	docker build --target build -f infra/docker/backend.Dockerfile -t heatroute-api:test .

web-test:
	pnpm test

test: backend-test web-test

lint:
	pnpm lint

typecheck:
	pnpm typecheck

build:
	docker compose build

logs:
	docker compose logs --tail 200 -f
