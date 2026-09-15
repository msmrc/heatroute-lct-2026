# HeatRoute demo VPS: deployment and updates

## Deployment contract

- Server: `root@130.49.150.217`
- Checkout: `/opt/heatroute`
- Private repository: `git@github.com:msmrc/heatroute-lct-2026.git` with a repository-scoped,
  read-only deploy key configured through local `core.sshCommand`
- Deployed branch: `master`
- Compose files: `compose.yaml` + `compose.vps.yaml`
- Secrets: `/opt/heatroute/.env.vps`
- Public endpoint: `http://130.49.150.217/`

This is a public **demo** deployment. The application deliberately keeps its internal demo
principal because the current frontend has no login screen. Do not publish ports 5173, 8000,
55432 or 56379 on a non-loopback address. Before using real or sensitive data, add a domain and
TLS, implement the login UI, switch to
`HEATROUTE_ENV=production` and `HEATROUTE_DEMO_MODE=false`.

The real `.env.vps`, SSH keys, volumes and backups are server state. They must never be committed
or copied into issue/chat logs.

## Standard agent update procedure

Connect through SSH, then run each block deliberately. Do not turn this into `git reset --hard` or
delete volumes to fix an update.

```bash
cd /opt/heatroute
umask 022
test "$(git rev-parse --show-toplevel)" = /opt/heatroute
test "$(git branch --show-current)" = master
test -z "$(git status --porcelain)" || { echo 'STOP: VPS checkout is dirty'; exit 1; }
```

Record the current release and create a database backup before migrations:

```bash
previous_sha=$(git rev-parse HEAD)
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p /opt/heatroute/backups
set -a
. /opt/heatroute/.env.vps
set +a
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
  > "/opt/heatroute/backups/heatroute-${timestamp}-${previous_sha}.dump"
chmod 600 "/opt/heatroute/backups/heatroute-${timestamp}-${previous_sha}.dump"
```

Fast-forward and validate the effective configuration before touching running services:

```bash
git fetch origin master
git merge --ff-only origin/master
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml config --quiet
```

Build and deploy. `migrate` runs Alembic before the API becomes healthy.

```bash
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml build --pull
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  up -d --remove-orphans --wait --wait-timeout 300
```

Verify all layers, not just the public HTML:

```bash
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml ps
curl --fail --silent http://127.0.0.1:8000/api/v1/health/ready
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  exec -T api python -m heatroute seed-demo --wait 60
curl --fail --silent --user 'heatroute:THE_DEMO_PASSWORD' http://127.0.0.1/
```

Report the deployed commit (`git rev-parse HEAD`), Compose service status, readiness response and
seeded `workspace_url`. Never put the real passwords or `.env.vps` contents into the report.

## If an update fails

First capture diagnostics; do not remove volumes:

```bash
cd /opt/heatroute
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml ps -a
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml logs --tail 300
```

If rollback is required, check out the previously recorded commit without rewriting branch
history, rebuild it and repeat the health checks:

```bash
git switch --detach "$previous_sha"
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml build
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  up -d --remove-orphans --wait --wait-timeout 300
```

After the incident is understood, return to the deployment branch with `git switch master`.
Restoring the database dump is a separate, destructive operation; do it only with explicit owner
approval and after preserving the current database.

## Routine operations

```bash
cd /opt/heatroute
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml ps
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml logs -f --tail 200
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml restart api worker scheduler web gateway
```

Rotate the SSH deploy key in GitHub and on the VPS as one operation. DNS is intentionally out of
scope until a domain is selected.
