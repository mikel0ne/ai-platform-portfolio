#!/usr/bin/env bash
# =============================================================================
# 05-setup-registry-ingress.sh
# Exposes the local registry as `registry.local` (no port).
#
# Result:
#   http://registry.local           -- Traefik 'web' entrypoint (80)
#   https://registry.local          -- Traefik 'websecure' entrypoint (443, default cert)
#   localhost:30500                 -- direct NodePort (fallback)
#
# How it works:
#   - Traefik IngressRoute with Host(registry.local) -> svc registry:5000.
#   - The Docker daemon (Rancher Desktop moby) trusts registry.local as an
#     insecure registry, so it accepts Traefik's default self-signed cert.
#   - k3s containerd does the same through /etc/rancher/k3s/registries.yaml.
#   - /etc/hosts inside the rancher-desktop WSL distro maps 127.0.0.1 registry.local
#     so containerd can resolve the host.
#   - The Windows hosts file is updated with infra/scripts/add-registry-host.ps1.
#
# Requires:
#   - 02-setup-registry.sh run first
#   - wsl.exe available (Windows host) to reach the rancher-desktop WSL distro
# =============================================================================

set -euo pipefail

NS="registry"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# Works from Git Bash on Windows or from WSL; the wsl calls below target the
# `rancher-desktop` distro.
have_wsl() { command -v wsl.exe >/dev/null 2>&1 || command -v wsl >/dev/null 2>&1; }
WSL_BIN="$(command -v wsl.exe || command -v wsl)"

# =========================================================================
# 0. Requirements
# =========================================================================
step "Checking requirements..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get svc registry -n "$NS" &>/dev/null || \
  fail "registry service not found in namespace '$NS'. Run 02-setup-registry.sh first"
have_wsl || fail "wsl(.exe) not found -- this script assumes Windows + Rancher Desktop"
ok "Requirements OK"

# =========================================================================
# 1. Traefik IngressRoute on `web` + `websecure` with the default TLS cert
# =========================================================================
step "Applying the registry.local IngressRoute (web + websecure)..."
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
ok "IngressRoute applied"

# =========================================================================
# 2. /etc/hosts inside the rancher-desktop WSL distro (for containerd)
# =========================================================================
step "Adding registry.local to the rancher-desktop WSL hosts file..."
"${WSL_BIN}" -d rancher-desktop -e sh -c '
  grep -q "registry.local" /etc/hosts || echo "127.0.0.1 registry.local" >> /etc/hosts
'
ok "WSL hosts ready"

# =========================================================================
# 3. /etc/docker/daemon.json: insecure-registries
# =========================================================================
step "Marking registry.local as an insecure registry in the Docker daemon..."
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
ok "daemon.json written, HUP sent to dockerd"

# =========================================================================
# 4. /etc/rancher/k3s/registries.yaml: insecure mirror
# =========================================================================
step "Configuring k3s containerd for registry.local..."
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
ok "registries.yaml written, k3s restarted"

# =========================================================================
# 5. Verification
# =========================================================================
step "Checking registry.local..."
sleep 4
http_status=$(curl -sk -o /dev/null -w "%{http_code}" https://registry.local/v2/ || echo "000")
if [ "${http_status}" = "200" ]; then
  ok "https://registry.local/v2/ answers 200"
else
  warn "https://registry.local/v2/ returned ${http_status} (it can take a few seconds)"
fi

# =========================================================================
# 6. Windows hosts file reminder
# =========================================================================
echo ""
echo "==> For the Windows HOST to resolve registry.local (once, as Administrator):"
echo "      infra\\scripts\\add-registry-host.ps1"
echo "    Or by hand, in an elevated PowerShell:"
echo "      Add-Content C:\\Windows\\System32\\drivers\\etc\\hosts '127.0.0.1 registry.local'"
echo ""
echo "==> End-to-end test:"
echo "      docker pull hello-world:latest"
echo "      docker tag hello-world:latest registry.local/hello-world:test"
echo "      docker push registry.local/hello-world:test"
echo "      curl -sk https://registry.local/v2/_catalog"
echo ""
warn "Persistence: daemon.json and registries.yaml live inside the rancher-desktop"
warn "WSL distro and are regenerated by Reset Kubernetes / Factory Reset."
warn "After a reset, run this script again."
