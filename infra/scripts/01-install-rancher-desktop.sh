#!/usr/bin/env bash
# =============================================================================
# 01-install-rancher-desktop.sh
# Instala Rancher Desktop con k3s en Windows via winget (ejecutar desde WSL2)
# =============================================================================

set -euo pipefail

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 1. Requisitos
# -----------------------------------------------------------------------------
step "Verificando requisitos previos..."

# winget (ejecutable de Windows desde WSL2)
if ! command -v winget.exe &>/dev/null; then
  fail "winget.exe no encontrado. Asegúrate de ejecutar este script desde WSL2."
fi
ok "winget disponible"

# RAM mínima (4 GB)
ram_kb=$(grep MemTotal /proc/meminfo | awk '{print $2}')
ram_gb=$(( ram_kb / 1024 / 1024 ))
if (( ram_gb < 4 )); then
  fail "RAM insuficiente: ${ram_gb} GB. Se requieren al menos 4 GB."
fi
ok "RAM disponible: ${ram_gb} GB"

# Ya instalado?
if winget.exe list --id SUSE.RancherDesktop 2>/dev/null | grep -q "SUSE.RancherDesktop"; then
  warn "Rancher Desktop ya está instalado."
  warn "Para actualizar: winget.exe upgrade SUSE.RancherDesktop"
  exit 0
fi

# -----------------------------------------------------------------------------
# 2. Instalar WSL2 + Rancher Desktop
# -----------------------------------------------------------------------------
step "Instalando Rancher Desktop..."

winget.exe install \
  --id SUSE.RancherDesktop \
  --accept-package-agreements \
  --accept-source-agreements \
  --silent

ok "Rancher Desktop instalado"

# -----------------------------------------------------------------------------
# 3. Post-instalación
# -----------------------------------------------------------------------------
step "Pasos post-instalación:"
cat <<EOF

  Al abrir Rancher Desktop por primera vez:
    1. Selecciona 'dockerd (moby)' como runtime
    2. Deja k3s activado (viene por defecto)
    3. Espera ~2 minutos a que el clúster arranque

  Si falla al arrancar, ejecuta desde WSL2:
    wsl.exe --install Ubuntu --no-launch
    wsl.exe --import rancher-desktop-data ~/.local/rancher-desktop-data \
      '/mnt/c/Program Files/Rancher Desktop/resources/resources/win32/distro-0.94.tar' \
      --version 2

  Cuando esté listo, ejecuta:
    bash scripts/02-setup-registry.sh

EOF
