#!/usr/bin/env bash
# =============================================================================
# 04-setup-postgres-ingress.sh
# Exposes the shared PostgreSQL to the host with a clean address.
#
# Result:
#   localhost:5432        -- immediately (Traefik TCP through the k3s Klipper LB)
#   postgres.local:5432   -- after running add-postgres-host.ps1 as Administrator
#
# How it works:
#   Traefik (the k3s/Rancher Desktop LoadBalancer) exposes a 'postgres' TCP
#   entrypoint on localhost:5432 through the k3s Klipper LB, and an
#   IngressRouteTCP forwards it to the postgres service in the infra namespace.
#
# Requires 03-setup-postgres.sh to have run first.
# =============================================================================

set -euo pipefail

NS="infra"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =========================================================================
# 0. Requirements
# =========================================================================
step "Checking requirements..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get svc postgres -n "$NS" &>/dev/null || \
  fail "postgres service not found in namespace $NS. Run 03-setup-postgres.sh first"
ok "Requirements OK"

# =========================================================================
# 1. HelmChartConfig -- 'postgres' TCP entrypoint on Traefik port 5432
# =========================================================================
step "Adding the postgres TCP entrypoint (5432) to Traefik..."
kubectl apply -f - <<'EOF'
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    ports:
      postgres:
        port: 5432
        expose:
          default: true
        exposedPort: 5432
        protocol: TCP
EOF
ok "HelmChartConfig applied"

# =========================================================================
# 2. Wait for Traefik to restart
# =========================================================================
step "Waiting for Traefik to pick up the new configuration (max. 90 s)..."
kubectl rollout status deployment/traefik -n kube-system --timeout=90s
ok "Traefik ready"

# =========================================================================
# 3. IngressRouteTCP
# =========================================================================
step "Creating the postgres IngressRouteTCP..."
kubectl apply -f - <<'EOF'
apiVersion: traefik.io/v1alpha1
kind: IngressRouteTCP
metadata:
  name: postgres
  namespace: infra
spec:
  entryPoints:
    - postgres
  routes:
    - match: HostSNI(*)
      services:
        - name: postgres
          port: 5432
EOF
ok "IngressRouteTCP created"

# =========================================================================
# 4. Check that port 5432 is exposed on localhost
# =========================================================================
step "Checking that localhost:5432 is reachable..."
sleep 5
if command -v nc &>/dev/null; then
  if nc -z 127.0.0.1 5432 2>/dev/null; then
    ok "Port 5432 reachable on 127.0.0.1"
  else
    warn "Port 5432 does not answer yet. Wait a few seconds and try again."
  fi
fi

# =========================================================================
# 5. Connection test with psql inside the pod
# =========================================================================
step "Testing the postgres connection from inside the pod..."
PG_POD=$(kubectl get pod -n "$NS" -l app=postgres -o jsonpath="{.items[0].metadata.name}")
kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U appuser -d appdb -c "SELECT 'IngressRouteTCP OK' AS result;"
ok "Connection verified"

# =========================================================================
# 6. Windows hosts file
# =========================================================================
step "To use postgres.local instead of localhost (once, as Administrator):"
echo ""
echo "  Run as Administrator:"
echo "    infra\scripts\add-postgres-host.ps1"
echo ""
echo "  Or by hand in an elevated PowerShell:"
echo "    Add-Content C:\Windows\System32\drivers\etc\hosts '127.0.0.1 postgres.local'"
echo ""

# =========================================================================
# 7. Summary
# =========================================================================
echo "============================================================"
echo "  PostgreSQL -- access URLs"
echo "============================================================"
echo ""
echo "  EXTERNAL (host / local dev):"
echo "  localhost:5432       <-- available right away"
echo "  postgres.local:5432  <-- after add-postgres-host.ps1"
echo ""
echo "  JDBC (local dev):"
echo "  jdbc:postgresql://postgres.local:5432/appdb"
echo ""
echo "  INTERNAL (inside Kubernetes / pods):"
echo "  jdbc:postgresql://postgres.infra.svc.cluster.local:5432/appdb"
echo ""
echo "  NodePort (alternative without Traefik):"
echo "  jdbc:postgresql://localhost:30532/appdb"
echo ""
echo "  User/password: POSTGRES_USER / POSTGRES_PASSWORD from infra/.env.local  |  DB: appdb"
echo "============================================================"
