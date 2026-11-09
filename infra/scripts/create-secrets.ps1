# =============================================================================
# create-secrets.ps1
# =============================================================================
# Crea Secrets de Kubernetes a partir de un fichero .env local.
# El fichero .env NUNCA debe commitearse (está en .gitignore).
#
# Uso:
#   .\infra\scripts\create-secrets.ps1 -Namespace mi-namespace -SecretName mi-secret -EnvFile .env
#
# Formato del fichero .env:
#   DB_PASSWORD=mi-password-seguro
#   API_KEY=abc123
#   OTRO_SECRETO=valor
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

# ── Validaciones ──────────────────────────────────────────────────────────────
if (-not (Test-Path $EnvFile)) {
    Write-Error "No se encuentra el fichero '$EnvFile'. Créalo copiando .env.example y rellenando los valores."
    exit 1
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Error "kubectl no está disponible en el PATH."
    exit 1
}

# ── Leer .env ─────────────────────────────────────────────────────────────────
$literals = [System.Collections.Generic.List[string]]::new()

foreach ($line in Get-Content $EnvFile) {
    # Ignorar líneas vacías y comentarios
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) {
        continue
    }

    if ($line -notmatch "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
        Write-Warning "Línea ignorada (formato inválido): $line"
        continue
    }

    $key   = $Matches[1]
    $value = $Matches[2].Trim('"').Trim("'")

    $literals.Add("--from-literal=${key}=${value}")
    Write-Host "  + $key" -ForegroundColor DarkGray
}

if ($literals.Count -eq 0) {
    Write-Error "El fichero '$EnvFile' no contiene ninguna variable válida."
    exit 1
}

# ── Asegurar que el namespace existe ─────────────────────────────────────────
$nsExists = kubectl get namespace $Namespace --ignore-not-found 2>$null
if (-not $nsExists) {
    Write-Host "Namespace '$Namespace' no existe, creándolo..." -ForegroundColor Yellow
    if (-not $DryRun) {
        kubectl create namespace $Namespace | Out-Null
    }
}

# ── Construir y ejecutar el comando kubectl ────────────────────────────────────
$kubectlArgs = @(
    "create", "secret", "generic", $SecretName,
    "-n", $Namespace,
    "--save-config",
    "--dry-run=client",   # generamos el YAML
    "-o", "yaml"
) + $literals

Write-Host ""
Write-Host "Creando/actualizando Secret '$SecretName' en namespace '$Namespace'..." -ForegroundColor Cyan

if ($DryRun) {
    Write-Host "[DRY-RUN] Se habría ejecutado:" -ForegroundColor Yellow
    Write-Host "  kubectl $($kubectlArgs -join ' ')" -ForegroundColor DarkGray
} else {
    # Genera el YAML y lo aplica con kubectl apply (idempotente)
    $yaml = kubectl @kubectlArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Error generando el Secret."
        exit 1
    }

    $yaml | kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Error aplicando el Secret."
        exit 1
    }

    Write-Host "✅ Secret '$SecretName' creado/actualizado correctamente." -ForegroundColor Green
}

Write-Host ""
Write-Host "💡 Verifica con: kubectl get secret $SecretName -n $Namespace" -ForegroundColor DarkGray
