# =============================================================================
# create-secrets.ps1
# =============================================================================
# Creates a Kubernetes Secret from a local .env file.
# The .env file must never be committed (it is gitignored).
#
# Usage:
#   .\infra\scripts\create-secrets.ps1 -Namespace my-namespace -SecretName my-secret -EnvFile .env
#
# .env format:
#   DB_PASSWORD=<value>
#   API_KEY=<value>
# =============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Namespace,

    [Parameter(Mandatory = $true)]
    [string]$SecretName,

    [Parameter(Mandatory = $false)]
    [string]$EnvFile = ".env",

    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Validation ────────────────────────────────────────────────────────────────
if (-not (Test-Path $EnvFile)) {
    Write-Error "File '$EnvFile' not found. Create it from infra/.env.local.example and fill in the values."
    exit 1
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Error "kubectl is not on PATH."
    exit 1
}

# ── Read .env ─────────────────────────────────────────────────────────────────
$literals = [System.Collections.Generic.List[string]]::new()

$lineNumber = 0
foreach ($line in Get-Content $EnvFile) {
    $lineNumber++
    # Skip blank lines and comments
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) {
        continue
    }

    if ($line -notmatch "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
        Write-Warning "Line $lineNumber skipped (invalid format)"
        continue
    }

    $key   = $Matches[1]
    $value = $Matches[2].Trim('"').Trim("'")

    $literals.Add("--from-literal=${key}=${value}")
    Write-Host "  + $key" -ForegroundColor DarkGray
}

if ($literals.Count -eq 0) {
    Write-Error "File '$EnvFile' contains no valid variable."
    exit 1
}

# ── Make sure the namespace exists ────────────────────────────────────────────
$nsExists = kubectl get namespace $Namespace --ignore-not-found 2>$null
if (-not $nsExists) {
    Write-Host "Namespace '$Namespace' does not exist, creating it..." -ForegroundColor Yellow
    if (-not $DryRun) {
        kubectl create namespace $Namespace | Out-Null
    }
}

# ── Build and run the kubectl command ─────────────────────────────────────────
$kubectlArgs = @(
    "create", "secret", "generic", $SecretName,
    "-n", $Namespace,
    "--save-config",
    "--dry-run=client",   # renders the YAML only
    "-o", "yaml"
) + $literals

Write-Host ""
Write-Host "Creating/updating Secret '$SecretName' in namespace '$Namespace'..." -ForegroundColor Cyan

if ($DryRun) {
    Write-Host "[DRY-RUN] Would have run:" -ForegroundColor Yellow
    $masked = $kubectlArgs | ForEach-Object { $_ -replace '^(--from-literal=[^=]+)=.*$', '$1=***' }
    Write-Host "  kubectl $($masked -join ' ')" -ForegroundColor DarkGray
} else {
    # Renders the YAML and pipes it to kubectl apply (idempotent)
    $yaml = kubectl @kubectlArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not render the Secret."
        exit 1
    }

    $yaml | kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not apply the Secret."
        exit 1
    }

    Write-Host "[ok] Secret '$SecretName' created/updated." -ForegroundColor Green
}

Write-Host ""
Write-Host "Check with: kubectl get secret $SecretName -n $Namespace" -ForegroundColor DarkGray
