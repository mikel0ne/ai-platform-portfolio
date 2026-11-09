# =============================================================================
# 01-install-rancher-desktop.ps1
# Installs Rancher Desktop (k3s) on Windows through winget
# =============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step { param($msg) Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-OK   { param($msg) Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn { param($msg) Write-Host "    [!]  $msg" -ForegroundColor Yellow }
function Write-Fail { param($msg) Write-Host "    [X]  $msg" -ForegroundColor Red }

# -----------------------------------------------------------------------------
# 1. Requirements
# -----------------------------------------------------------------------------
Write-Step "Checking requirements..."

# winget
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Fail "winget not found. Install it from https://aka.ms/getwinget"
    exit 1
}
Write-OK "winget available"

# WSL2
try {
    $wslStatus = (wsl --status 2>&1) -join ' '
    Write-OK "WSL2 available"
} catch {
    Write-Warn "WSL2 is not installed. Rancher Desktop installs it during its setup."
    Write-Warn "If the installation fails, run: wsl --install"
}

# Minimum RAM (4 GB)
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
if ($ramGB -lt 4) {
    Write-Fail "Not enough RAM: ${ramGB} GB. At least 4 GB are required."
    exit 1
}
Write-OK "Available RAM: ${ramGB} GB"

# Already installed?
$existing = winget list --id SUSE.RancherDesktop 2>&1
if ($existing -match "SUSE.RancherDesktop") {
    Write-Warn "Rancher Desktop is already installed. Run winget upgrade SUSE.RancherDesktop to upgrade."
    exit 0
}

# -----------------------------------------------------------------------------
# 2. Install Rancher Desktop
# -----------------------------------------------------------------------------
Write-Step "Installing Rancher Desktop..."

winget install --id SUSE.RancherDesktop `
    --accept-package-agreements `
    --accept-source-agreements `
    --silent

if ($LASTEXITCODE -ne 0) {
    Write-Fail "Installation failed. Check the winget logs."
    exit 1
}

Write-OK "Rancher Desktop installed"

# -----------------------------------------------------------------------------
# 3. Post-install instructions
# -----------------------------------------------------------------------------
Write-Step "Post-install steps (manual)..."

Write-Host @"

    Rancher Desktop is installed. The first time you open it:

    1. Choose 'dockerd (moby)' as container runtime
       (for docker CLI compatibility)
    2. Keep k3s enabled (the default)
    3. Wait for the cluster to start (~2 minutes the first time)

    Once it is ready, run the next script (Git Bash):
        ./infra/scripts/02-setup-registry.sh

"@ -ForegroundColor White

Write-Step "Checking the available commands after a session restart..."
Write-Warn "You may need to restart the terminal for kubectl, helm and docker to be on PATH."
