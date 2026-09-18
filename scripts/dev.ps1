param(
    [Parameter(Position = 0)]
    [ValidateSet('bootstrap', 'up', 'down', 'test', 'backend-test', 'web-test', 'lint', 'typecheck', 'build', 'logs')]
    [string]$Action = 'test'
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ToolingRoot = if ($env:HEATROUTE_TOOLING_ROOT) {
    [System.IO.Path]::GetFullPath($env:HEATROUTE_TOOLING_ROOT)
} else {
    Join-Path $ProjectRoot '.tooling'
}
$ComposeProject = if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'heatroute' }
if (-not $env:COMPOSE_BAKE) { $env:COMPOSE_BAKE = 'false' }
$env:PNPM_HOME = Join-Path $ToolingRoot 'pnpm\home'
$env:PNPM_STORE_DIR = Join-Path $ToolingRoot 'pnpm\store'
$env:npm_config_cache = Join-Path $ToolingRoot 'npm-cache'
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $ToolingRoot 'playwright\browsers'

$ComposeRoot = $ProjectRoot
if ($ProjectRoot -match '[^\x00-\x7F]') {
    $rootBytes = [System.Text.Encoding]::UTF8.GetBytes($ProjectRoot)
    $rootHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($rootBytes)).Substring(0, 12)
    $junctionBase = Join-Path ([System.IO.Path]::GetTempPath()) 'heatroute-compose'
    $junctionPath = Join-Path $junctionBase $rootHash
    New-Item -ItemType Directory -Force -Path $junctionBase | Out-Null
    if (-not (Test-Path -LiteralPath $junctionPath)) {
        New-Item -ItemType Junction -Path $junctionPath -Target $ProjectRoot | Out-Null
    }
    $junctionTarget = (Get-Item -LiteralPath $junctionPath).Target
    if ($junctionTarget -ne $ProjectRoot) {
        throw "Compose workspace junction points to an unexpected location: $junctionPath"
    }
    $ComposeRoot = $junctionPath
}

function Assert-LastExitCode([string]$CommandName) {
    if ($LASTEXITCODE -ne 0) { throw "Command '$CommandName' failed with exit code $LASTEXITCODE." }
}

@($env:PNPM_HOME, $env:PNPM_STORE_DIR, $env:npm_config_cache, $env:PLAYWRIGHT_BROWSERS_PATH) |
    ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

function Assert-Docker {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop Linux engine is not running.' }
}

Push-Location $ComposeRoot
try {
    switch ($Action) {
        'bootstrap' { pnpm install --frozen-lockfile; Assert-LastExitCode 'pnpm install' }
        'up' { Assert-Docker; docker compose -p $ComposeProject up --build -d --wait; Assert-LastExitCode 'docker compose up' }
        'down' { docker compose -p $ComposeProject down; Assert-LastExitCode 'docker compose down' }
        'backend-test' { Assert-Docker; docker build --target test -f infra/docker/backend.Dockerfile -t heatroute-api:test .; Assert-LastExitCode 'Java backend verify' }
        'web-test' { pnpm test; Assert-LastExitCode 'vitest' }
        'test' {
            Assert-Docker
            docker build --target test -f infra/docker/backend.Dockerfile -t heatroute-api:test .
            Assert-LastExitCode 'Java backend verify'
            pnpm test
            Assert-LastExitCode 'vitest'
        }
        'lint' { pnpm lint; Assert-LastExitCode 'eslint' }
        'typecheck' { pnpm typecheck; Assert-LastExitCode 'tsc' }
        'build' { Assert-Docker; docker compose -p $ComposeProject build; Assert-LastExitCode 'docker compose build' }
        'logs' { docker compose -p $ComposeProject logs --tail 200 -f; Assert-LastExitCode 'docker compose logs' }
    }
}
finally { Pop-Location }
