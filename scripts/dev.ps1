param(
    [Parameter(Position = 0)]
    [ValidateSet('bootstrap', 'up', 'down', 'migrate', 'seed-demo', 'test', 'test-integration', 'test-e2e', 'benchmark-demo', 'lint', 'typecheck', 'export-openapi', 'worker-smoke', 'security-audit', 'logs')]
    [string]$Action = 'test'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ToolingRoot = 'E:\job\.tooling'
$env:UV_CACHE_DIR = Join-Path $ToolingRoot 'uv\cache'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $ToolingRoot 'uv\python'
$env:UV_PROJECT_ENVIRONMENT = Join-Path $ProjectRoot '.venv'
$env:PNPM_HOME = Join-Path $ToolingRoot 'pnpm\home'
$env:PNPM_STORE_DIR = Join-Path $ToolingRoot 'pnpm\store'
$env:npm_config_cache = Join-Path $ToolingRoot 'npm-cache'
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $ToolingRoot 'playwright\browsers'
$env:TEMP = Join-Path $ToolingRoot 'tmp\heatroute'
$env:TMP = $env:TEMP
$env:PYTHONPATH = Join-Path $ProjectRoot 'apps\api\src'

function Assert-LastExitCode([string]$CommandName) {
    if ($LASTEXITCODE -ne 0) {
        throw "Command '$CommandName' failed with exit code $LASTEXITCODE."
    }
}

@(
    $env:UV_CACHE_DIR,
    $env:UV_PYTHON_INSTALL_DIR,
    $env:PNPM_HOME,
    $env:PNPM_STORE_DIR,
    $env:npm_config_cache,
    $env:PLAYWRIGHT_BROWSERS_PATH,
    $env:TEMP
) | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

Push-Location $ProjectRoot
try {
    switch ($Action) {
        'bootstrap' {
            uv python install 3.12.11
            Assert-LastExitCode 'uv python install'
            uv sync --frozen
            Assert-LastExitCode 'uv sync'
            pnpm install --frozen-lockfile
            Assert-LastExitCode 'pnpm install'
        }
        'up' {
            docker info *> $null
            if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine is not running.' }
            docker compose up --build -d
            Assert-LastExitCode 'docker compose up'
        }
        'down' {
            docker compose down
            Assert-LastExitCode 'docker compose down'
        }
        'migrate' {
            docker compose run --rm migrate
            Assert-LastExitCode 'alembic migration container'
        }
        'seed-demo' {
            docker compose exec -T api python -m heatroute seed-demo --wait 30
            Assert-LastExitCode 'seed demo'
        }
        'test' {
            uv run pytest -m 'not integration'
            Assert-LastExitCode 'pytest unit'
            pnpm test
            Assert-LastExitCode 'vitest'
        }
        'test-integration' {
            uv run pytest -m integration
            Assert-LastExitCode 'pytest integration'
        }
        'test-e2e' {
            uv run pytest -m integration tests/integration/test_demo_run.py tests/integration/test_m4_workspace_api.py
            Assert-LastExitCode 'pytest e2e workflow'
        }
        'benchmark-demo' {
            uv run python scripts/benchmark_m3.py
            Assert-LastExitCode 'demo benchmark'
        }
        'lint' {
            uv run ruff check apps/api/src tests
            Assert-LastExitCode 'ruff'
            pnpm lint
            Assert-LastExitCode 'eslint'
        }
        'typecheck' {
            uv run mypy apps/api/src
            Assert-LastExitCode 'mypy'
            pnpm typecheck
            Assert-LastExitCode 'tsc'
        }
        'export-openapi' {
            uv run python -m heatroute export-openapi
            Assert-LastExitCode 'OpenAPI export'
        }
        'worker-smoke' {
            uv run python -m heatroute worker-smoke
            Assert-LastExitCode 'worker smoke'
        }
        'security-audit' {
            uv run python scripts/security_audit.py
            Assert-LastExitCode 'security audit'
        }
        'logs' {
            docker compose logs --tail 200 -f
            Assert-LastExitCode 'docker compose logs'
        }
    }
}
finally {
    Pop-Location
}
