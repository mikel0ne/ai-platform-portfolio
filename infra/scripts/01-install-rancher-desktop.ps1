# =============================================================================
# 01-install-rancher-desktop.ps1
# Instala Rancher Desktop con k3s en Windows via winget
# =============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step { param($msg) Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-OK   { param($msg) Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "    [!]  $msg" -ForegroundColor Yellow }
function Write-Fail { param($msg) Write-Host "    [X]  $msg" -ForegroundColor Red }

# -----------------------------------------------------------------------------
# 1. Verificar requisitos
# -----------------------------------------------------------------------------
Write-Step "Verificando requisitos previos..."

# winget
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Fail "winget no encontrado. Instálalo desde https://aka.ms/getwinget"
    exit 1
}
Write-OK "winget disponible"

# WSL2
try {
    $wslStatus = (wsl --status 2>&1) -join ' '
    Write-OK "WSL2 disponible"
} catch {
    Write-Warn "WSL2 no está instalado. Rancher Desktop lo instalará automáticamente durante su setup."
    Write-Warn "Si la instalación falla, ejecuta manualmente: wsl --install"
}

# RAM mínima (4 GB)
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
if ($ramGB -lt 4) {
    Write-Fail "RAM insuficiente: ${ramGB} GB. Se requieren al menos 4 GB."
    exit 1
}
Write-OK "RAM disponible: ${ramGB} GB"

# Comprobar si ya está instalado
$existing = winget list --id SUSE.RancherDesktop 2>&1
if ($existing -match "SUSE.RancherDesktop") {
    Write-Warn "Rancher Desktop ya está instalado. Ejecuta winget upgrade SUSE.RancherDesktop para actualizar."
    exit 0
}

# -----------------------------------------------------------------------------
# 2. Instalar Rancher Desktop
# -----------------------------------------------------------------------------
Write-Step "Instalando Rancher Desktop..."

winget install --id SUSE.RancherDesktop `
    --accept-package-agreements `
    --accept-source-agreements `
    --silent

if ($LASTEXITCODE -ne 0) {
    Write-Fail "Error durante la instalación. Revisa los logs de winget."
    exit 1
}

Write-OK "Rancher Desktop instalado correctamente"

# -----------------------------------------------------------------------------
# 3. Instrucciones post-instalación
# -----------------------------------------------------------------------------
Write-Step "Pasos post-instalación (manuales)..."

Write-Host @"

    Rancher Desktop se ha instalado. La primera vez que lo abras:

    1. Selecciona 'dockerd (moby)' como runtime de contenedores
       (para compatibilidad con comandos docker)
    2. Deja k3s activado (viene por defecto)
    3. Espera a que el clúster arranque (~2 minutos la primera vez)

    Cuando esté listo, ejecuta el siguiente script (Git Bash):
        ./infra/scripts/02-setup-registry.sh

"@ -ForegroundColor White

Write-Step "Verificando comandos disponibles tras reinicio de sesión..."
Write-Warn "Puede ser necesario reiniciar el terminal para que kubectl, helm y docker estén en el PATH."
