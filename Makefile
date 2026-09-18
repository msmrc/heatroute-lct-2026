.PHONY: bootstrap up down test backend-test web-test lint typecheck build logs

COMPOSE_PROJECT_NAME ?= heatroute

bootstrap:
	pnpm install --frozen-lockfile

up:
	docker compose -p $(COMPOSE_PROJECT_NAME) up --build -d --wait

down:
	docker compose -p $(COMPOSE_PROJECT_NAME) down

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
	docker compose -p $(COMPOSE_PROJECT_NAME) build

logs:
	docker compose -p $(COMPOSE_PROJECT_NAME) logs --tail 200 -f
