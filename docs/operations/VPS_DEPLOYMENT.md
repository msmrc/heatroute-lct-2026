# HeatRoute demo VPS: Java deployment and updates

## Contract

- checkout: `/opt/heatroute`, branch `master`;
- compose: `compose.yaml` + `compose.vps.yaml`;
- secrets: `/opt/heatroute/.env.vps`, mode 600, never committed;
- public endpoint: `https://130-49-150-217.sslip.io/`;
- Swagger UI: `https://130-49-150-217.sslip.io/api/v1/swagger-ui.html`;
- OpenAPI JSON: `https://130-49-150-217.sslip.io/api/v1/openapi`;
- services: PostGIS `db`, Java `api`, React/Nginx `web`, Caddy `gateway`;
- web CSP allows the CARTO Positron style, TileJSON, sprites, glyphs and sharded `*.basemaps.cartocdn.com` vector tiles used by MapLibre; its
  Referer policy preserves the origin required by the basemap service;
- only 22, 80 and 443 are public; 5173, 8000 and 55432 remain loopback/internal.

The demo VPS release is Java-only. Liquibase runs automatically during API startup. The current
server runs Ubuntu 26.04.1, which is newer than the official Ubuntu Server 22 acceptance target,
so a separate clean Ubuntu 22 rehearsal is still required for R9.

## Standard update

```bash
cd /opt/heatroute
umask 022
test "$(git rev-parse --show-toplevel)" = /opt/heatroute
test "$(git branch --show-current)" = master
test -z "$(git status --porcelain)" || { echo 'STOP: VPS checkout is dirty'; exit 1; }

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

git fetch origin master
git merge --ff-only origin/master
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml config --quiet
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  run --rm --no-deps db sh -lc \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql -h db -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -v ON_ERROR_STOP=1 -c "select 1" >/dev/null'
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml build --pull
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml \
  up -d --remove-orphans --wait --wait-timeout 300
```

The preflight TCP query is mandatory for an existing database volume. Changing
`POSTGRES_PASSWORD` in `.env.vps` does not rotate the password already stored for the PostgreSQL
role. If this query fails, stop before recreating containers, keep the previous application
running, and explicitly reconcile the role secret during a maintenance window. Never print the
secret or pass it as a literal command-line argument.

## Verification

```bash
docker compose --env-file .env.vps -f compose.yaml -f compose.vps.yaml ps
curl --fail --silent http://127.0.0.1:8000/api/v1/health/ready
curl --fail --silent http://127.0.0.1:8000/api/v1/openapi | grep -q '/api/v1/official/imports'
curl --fail --silent http://127.0.0.1:8000/api/v1/openapi | grep -q 'HeatRoute network calculation'
curl --fail --silent http://127.0.0.1:8000/api/v1/official/contracts/output.schema.json \
  | grep -q 'Strict four-type'
curl --fail --silent --resolve 130-49-150-217.sslip.io:443:127.0.0.1 \
  https://130-49-150-217.sslip.io/ | grep -q HeatRoute
```

For a real contract smoke, upload only a non-sensitive fixture already present in the checkout:

```bash
curl --fail --silent -F file=@datasets/official/lct-2026.geojson \
  http://127.0.0.1:8000/api/v1/official/imports
```

Record deployed SHA, service health and import state without printing secrets.

After the smoke calculation, verify the deployed HeatRoute algorithm version:

```bash
algorithm_version="$(
  curl --fail --silent http://127.0.0.1:8000/api/v1/official/runs/latest \
    | sed -n 's/.*"algorithm_version":"\([^"]*\)".*/\1/p'
)"
case "$algorithm_version" in
  heatroute-network-*) printf 'HeatRoute confirmed: %s\n' "$algorithm_version" ;;
  *) printf 'STOP: unexpected algorithm_version=%s\n' "$algorithm_version" >&2; exit 1 ;;
esac
```

The profile name is not sufficient evidence. Record the `algorithm_version`, run ID,
`created_at`, `completed_at`, connected demand count and all three issue arrays.

## Failure and rollback

Capture `docker compose ... ps -a` and `docker compose ... logs --tail 300` first. Do not delete
volumes. If rollback is required, detach at `$previous_sha`, rebuild and repeat health checks;
return to `master` after the incident is understood. Restoring a DB dump is destructive and needs
explicit owner approval.
