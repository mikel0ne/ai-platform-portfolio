#!/usr/bin/env bash
# =============================================================================
# 05-setup-registry-ingress.sh
# Configura acceso al registry local como `registry.local` (sin puerto).
#
# Resultado tras ejecutarlo:
#   http://registry.local           -- Traefik entrypoint web (80)
#   https://registry.local          -- Traefik entrypoint websecure (443, cert por defecto)
#   localhost:30500                 -- NodePort directo (fallback)
#
# Como funciona:
#   - IngressRoute Traefik con Host(registry.local) -> svc registry:5000.
#   - Docker daemon (moby de Rancher Desktop) confia en registry.local como
#     insecure-registry, asi acepta el cert auto-firmado por defecto de Traefik.
#   - k3s containerd usa /etc/rancher/k3s/registries.yaml para hacer lo mismo.
#   - /etc/hosts dentro de la WSL rancher-desktop apunta 127.0.0.1 registry.local
#     (para que containerd resuelva el host).
#   - El hosts file de Windows se actualiza con infra/scripts/add-registry-host.ps1.
#
# Requiere:
#   - 02-setup-registry.sh ejecutado previamente
#   - wsl.exe disponible (Windows host) para tocar la WSL rancher-desktop
# =============================================================================

set -euo pipefail

NS="registry"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# Detecta si tenemos wsl.exe (estamos en Git Bash sobre Windows o en WSL).
# El script asume que el host es Windows con Rancher Desktop; los `wsl` posteriores
# usan la distro `rancher-desktop`.
have_wsl() { command -v wsl.exe >/dev/null 2>&1 || command -v wsl >/dev/null 2>&1; }
WSL_BIN="$(command -v wsl.exe || command -v wsl)"

# =========================================================================
# 0. Requisitos
# =========================================================================
step "Verificando requisitos..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get svc registry -n "$NS" &>/dev/null || \
  fail "Servicio registry no encontrado en namespace '$NS'. Ejecuta primero 02-setup-registry.sh"
have_wsl || fail "No encuentro wsl(.exe) -- este script asume Windows + Rancher Desktop"
ok "Prerrequisitos OK"

# =========================================================================
# 1. IngressRoute Traefik en `web` + `websecure` con TLS por defecto
# =========================================================================
step "Aplicando IngressRoute registry.local (web + websecure)..."
kubectl apply -n "$NS" -f - <<EOF
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: registry
  namespace: ${NS}
spec:
  entryPoints:
    - web
    - websecure
  routes:
    - kind: Rule
      match: Host(\`registry.local\`)
      services:
        - name: registry
          port: 5000
  tls: {}
EOF
ok "IngressRoute aplicado"

# =========================================================================
# 2. /etc/hosts dentro de la WSL rancher-desktop (para que containerd resuelva)
# =========================================================================
step "Anadiendo registry.local al hosts de la WSL rancher-desktop..."
"${WSL_BIN}" -d rancher-desktop -e sh -c '
  grep -q "registry.local" /etc/hosts || echo "127.0.0.1 registry.local" >> /etc/hosts
'
ok "Hosts WSL listo"

# =========================================================================
# 3. /etc/docker/daemon.json: insecure-registries
# =========================================================================
step "Marcando registry.local como insecure-registry en el daemon de Docker..."
"${WSL_BIN}" -d rancher-desktop -e sh -c '
cat > /etc/docker/daemon.json <<JSON
{
  "min-api-version": "1.41",
  "seccomp-profile": "/etc/rancher-desktop/seccomp.json",
  "features": {
    "containerd-snapshotter": true
  },
  "insecure-registries": ["registry.local"]
}
JSON
pkill -HUP dockerd 2>/dev/null || true
'
ok "daemon.json escrito + HUP enviado a dockerd"

# =========================================================================
# 4. /etc/rancher/k3s/registries.yaml: mirror insecure
# =========================================================================
step "Configurando k3s containerd para registry.local..."
"${WSL_BIN}" -d rancher-desktop -e sh -c '
mkdir -p /etc/rancher/k3s
cat > /etc/rancher/k3s/registries.yaml <<YAML
mirrors:
  registry.local:
    endpoint:
      - "http://registry.local"
configs:
  registry.local:
    tls:
      insecure_skip_verify: true
YAML
( rc-service k3s restart 2>&1 || /etc/init.d/k3s restart 2>&1 ) | tail -3
'
ok "registries.yaml escrito + k3s reiniciado"

# =========================================================================
# 5. Verificacion
# =========================================================================
step "Verificando registry.local..."
sleep 4
http_status=$(curl -sk -o /dev/null -w "%{http_code}" https://registry.local/v2/ || echo "000")
if [ "${http_status}" = "200" ]; then
  ok "https://registry.local/v2/ responde 200"
else
  warn "https://registry.local/v2/ devolvio ${http_status} (puede tardar unos segundos)"
fi

# =========================================================================
# 6. Recordatorio del hosts de Windows
# =========================================================================
echo ""
echo "==> Para que el HOST Windows resuelva registry.local (solo una vez como Admin):"
echo "      infra\\scripts\\add-registry-host.ps1"
echo "    O manualmente, en una PowerShell elevada:"
echo "      Add-Content C:\\Windows\\System32\\drivers\\etc\\hosts '127.0.0.1 registry.local'"
echo ""
echo "==> Prueba de extremo a extremo:"
echo "      docker pull hello-world:latest"
echo "      docker tag hello-world:latest registry.local/hello-world:test"
echo "      docker push registry.local/hello-world:test"
echo "      curl -sk https://registry.local/v2/_catalog"
echo ""
warn "Persistencia: daemon.json y registries.yaml viven dentro de la WSL"
warn "rancher-desktop y se regeneran si haces Reset Kubernetes/Factory Reset."
warn "Tras un reset, vuelve a ejecutar este script."
