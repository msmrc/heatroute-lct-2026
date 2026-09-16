param(
    [Parameter(Position = 0)]
    [ValidateSet('bootstrap', 'up', 'down', 'test', 'backend-test', 'web-test', 'lint', 'typecheck', 'build', 'logs')]
    [string]$Action = 'test'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ToolingRoot = 'E:\job\.tooling'
$env:PNPM_HOME = Join-Path $ToolingRoot 'pnpm\home'
$env:PNPM_STORE_DIR = Join-Path $ToolingRoot 'pnpm\store'
$env:npm_config_cache = Join-Path $ToolingRoot 'npm-cache'
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $ToolingRoot 'playwright\browsers'
$env:TEMP = Join-Path $ToolingRoot 'tmp\heatroute'
$env:TMP = $env:TEMP

function Assert-LastExitCode([string]$CommandName) {
    if ($LASTEXITCODE -ne 0) { throw "Command '$CommandName' failed with exit code $LASTEXITCODE." }
}

@($env:PNPM_HOME, $env:PNPM_STORE_DIR, $env:npm_config_cache, $env:PLAYWRIGHT_BROWSERS_PATH, $env:TEMP) |
    ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

function Assert-Docker {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine is not running.' }
}

Push-Location $ProjectRoot
try {
    switch ($Action) {
        'bootstrap' { pnpm install --frozen-lockfile; Assert-LastExitCode 'pnpm install' }
        'up' { Assert-Docker; docker compose up --build -d --wait; Assert-LastExitCode 'docker compose up' }
        'down' { docker compose down; Assert-LastExitCode 'docker compose down' }
        'backend-test' { Assert-Docker; docker build --target build -f infra/docker/backend.Dockerfile -t heatroute-api:test .; Assert-LastExitCode 'Java backend verify' }
        'web-test' { pnpm test; Assert-LastExitCode 'vitest' }
        'test' {
            Assert-Docker
            docker build --target build -f infra/docker/backend.Dockerfile -t heatroute-api:test .
            Assert-LastExitCode 'Java backend verify'
            pnpm test
            Assert-LastExitCode 'vitest'
        }
        'lint' { pnpm lint; Assert-LastExitCode 'eslint' }
        'typecheck' { pnpm typecheck; Assert-LastExitCode 'tsc' }
        'build' { Assert-Docker; docker compose build; Assert-LastExitCode 'docker compose build' }
        'logs' { docker compose logs --tail 200 -f; Assert-LastExitCode 'docker compose logs' }
    }
}
finally { Pop-Location }
