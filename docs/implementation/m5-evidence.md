# M5 export, resilience and acceptance evidence

Date: 2026-09-08

## Result

M5 is complete locally for the synthetic P0 scope. The checked-in commands build a production
web bundle, migrate an empty database, seed and finish a backend route, export the result, expose
operational signals and preserve it across restart. The application still does not claim the P1
production-operations work or the data-dependent engineering models listed in the README.

## Clean offline rehearsal

The final rehearsal ran from
`E:/job/.tooling/heatroute-m5-prod-rehearsal-20260908-1445`, not the repository checkout. It used
fresh named PostGIS and Redis volumes and only prebuilt local images via `--no-build --pull never`.
The Compose override set the default network to `internal: true`; Docker inspection returned
`true` and a request to `https://example.com` failed with DNS unavailable.

`python -m heatroute seed-demo --wait 30` created project
`61ecddea-fd1e-4fc0-b5b5-59961ce71bba`, revision
`f534ac6e-529e-4aa4-99cb-0ec43406cb5f` and run
`57522895-82e0-4b98-99e4-910c1f0b9919`. The run reached `succeeded/routes_found` with two
alternatives. Internal probes returned API `ready`, web HTTP 200 through Nginx and API `ready`
through the web same-origin proxy. Export sizes were 23,346 bytes GeoJSON, 10,388 JSON, 141 CSV
and 876 HTML. After restarting database, Redis, API, worker, scheduler and web, the same run still
returned `succeeded/routes_found` with two alternatives.

## Security and operations

| Check | Result | Evidence |
|---|---|---|
| SEC-07 XSS | PASS | Cost-catalog HTML exports use HTML escaping; a live integration export containing a script tag rendered only escaped text. |
| SEC-08 CSRF | PASS | Auth integration coverage rejects unsafe mutations without the session CSRF token; demo bypass remains development-only. |
| SEC-09 demo credentials | PASS | Container startup configuration rejects both production demo mode and the development session secret; an explicit non-demo production configuration is accepted. |
| SEC-10 CSV formula injection | PASS | Shared CSV serialization prefixes `=`, `+`, `-`, `@`, tab and carriage-return cells; unit and live export tests pass. |
| SEC-11 sensitive logging | PASS | API request logging is JSON and allowlists method/path/status/duration/request/workspace/user IDs; query, body, cookies and geometry are excluded. Nginx access logging is disabled. |
| SEC-12 repo/build secrets | PASS | `scripts/security_audit.py` scanned 168 repository text files; `.env`, private-key headers and common provider-token patterns were absent. API image environment/history contains no application secret. |

Migration `20260908_0015` adds workspace-scoped `audit_events`. Authenticated mutations store
request ID, actor, method, path, status and content type, never the request payload. The admin-only
audit endpoint was verified live. The admin-only Prometheus text endpoint exposes aggregate HTTP
method/status counters and method duration sums without path labels or user data. Readiness still
checks live PostGIS and Redis dependencies.

Raw uploads retain the M1 controls: streamed size limit, server-owned hash paths, filename
normalization, format allowlist, GDAL remote/extension restrictions, archive rejection and
workspace-scoped download access. No new parser was introduced in M5.

## Production web and browser QA

Vite builds static assets in the pinned Node stage. A pinned-digest
`nginxinc/nginx-unprivileged` image serves them on port 8080 as a non-root user and proxies
`/api/` to the backend. Responses include CSP, `nosniff`, `DENY` frame policy and no-referrer;
server version tokens and access logs are disabled.

The 1280×720 Playwright smoke loaded the persisted M4 run through port 5173, rendered the route,
opened the inspector passport with a real pointer click and resolved all four export links to the
same origin. A map-canvas overflow that intercepted inspector clicks at this breakpoint was found
and fixed. The final session had zero browser console errors.

## Verification matrix

- Python unit suite: 118 passed, 20 integration tests deselected.
- Live integration suite: 20 passed, including audit, export security and worker flows.
- Web Vitest: 3 files / 6 tests passed.
- Ruff, strict MyPy (51 source files), ESLint and TypeScript: passed.
- Web production build and generated OpenAPI client drift check: passed.
- Alembic check: no new upgrade operations; known cyclic-FK ordering warning only.
- Compose config, health checks, worker smoke, seed-demo and security audit: passed.
- Routing benchmark refreshed at `artifacts/benchmarks/m3-routing.json`: 20 m A* 499 ms,
  20 m Dijkstra 1,485 ms, 40 m A* 5,839 ms, 40 m Dijkstra 20,458 ms on this workstation.

The Vite build retains a non-blocking size warning for the lazy MapLibre chunk. Remote CI is not
claimed because the repository has no remote baseline commit; the equivalent local commands have
passed.
