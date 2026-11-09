#!/usr/bin/env bash
# =============================================================================
# 01-install-rancher-desktop.sh
# Installs Rancher Desktop (k3s) on Windows through winget. Run it from WSL2.
# =============================================================================

set -euo pipefail

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 1. Requirements
# -----------------------------------------------------------------------------
step "Checking requirements..."

# winget (Windows executable reachable from WSL2)
if ! command -v winget.exe &>/dev/null; then
  fail "winget.exe not found. Run this script from WSL2."
fi
ok "winget available"

# Minimum RAM (4 GB)
ram_kb=$(grep MemTotal /proc/meminfo | awk '{print $2}')
ram_gb=$(( ram_kb / 1024 / 1024 ))
if (( ram_gb < 4 )); then
  fail "Not enough RAM: ${ram_gb} GB. At least 4 GB are required."
fi
ok "Available RAM: ${ram_gb} GB"

# Already installed?
if winget.exe list --id SUSE.RancherDesktop 2>/dev/null | grep -q "SUSE.RancherDesktop"; then
  warn "Rancher Desktop is already installed."
  warn "To upgrade: winget.exe upgrade SUSE.RancherDesktop"
  exit 0
fi

# -----------------------------------------------------------------------------
# 2. Install WSL2 + Rancher Desktop
# -----------------------------------------------------------------------------
step "Installing Rancher Desktop..."

winget.exe install \
  --id SUSE.RancherDesktop \
  --accept-package-agreements \
  --accept-source-agreements \
  --silent

ok "Rancher Desktop installed"

# -----------------------------------------------------------------------------
# 3. Post-install
# -----------------------------------------------------------------------------
step "Post-install steps:"
cat <<EOF

  When opening Rancher Desktop for the first time:
    1. Choose 'dockerd (moby)' as container runtime
    2. Keep k3s enabled (the default)
    3. Wait ~2 minutes for the cluster to start

  If it fails to start, run from WSL2:
    wsl.exe --install Ubuntu --no-launch
    wsl.exe --import rancher-desktop-data ~/.local/rancher-desktop-data \
      '/mnt/c/Program Files/Rancher Desktop/resources/resources/win32/distro-0.94.tar' \
      --version 2

  Once it is ready, run:
    bash infra/scripts/02-setup-registry.sh

EOF
