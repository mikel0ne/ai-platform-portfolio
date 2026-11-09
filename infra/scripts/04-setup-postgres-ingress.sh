#!/usr/bin/env bash
# =============================================================================
# 04-setup-postgres-ingress.sh
# Configura el acceso a PostgreSQL con URL limpia desde el host.
#
# Resultado tras ejecutarlo:
#   localhost:5432        -- INMEDIATO (Traefik TCP via Klipper LB de k3s)
#   postgres.local:5432   -- tras ejecutar add-postgres-host.ps1 como Administrador
#
# Como funciona:
#   Traefik (LoadBalancer en k3s/Rancher Desktop) expone el entrypoint TCP
#   postgres en localhost:5432 a traves del Klipper LB de k3s.
#   El IngressRouteTCP enruta ese trafico al servicio postgres en namespace infra.
#
# Requiere: script 03-setup-postgres.sh ejecutado previamente
# =============================================================================

set -euo pipefail

NS="infra"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =========================================================================
# 0. Requisitos
# =========================================================================
step "Verificando requisitos..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get svc postgres -n "$NS" &>/dev/null || \
  fail "Servicio postgres no encontrado en namespace $NS. Ejecuta primero 03-setup-postgres.sh"
ok "Prerrequisitos OK"

# =========================================================================
# 1. HelmChartConfig -- entrypoint TCP postgres en Traefik puerto 5432
# =========================================================================
step "Anadiendo entrypoint TCP postgres (5432) a Traefik..."
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
ok "HelmChartConfig aplicado"

# =========================================================================
# 2. Esperar a que Traefik se reinicie
# =========================================================================
step "Esperando a que Traefik aplique la nueva configuracion (max. 90 s)..."
kubectl rollout status deployment/traefik -n kube-system --timeout=90s
ok "Traefik listo"

# =========================================================================
# 3. IngressRouteTCP
# =========================================================================
step "Creando IngressRouteTCP postgres..."
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
ok "IngressRouteTCP creado"

# =========================================================================
# 4. Verificar que el puerto 5432 esta expuesto en localhost
# =========================================================================
step "Verificando que localhost:5432 es accesible..."
sleep 5
if command -v nc &>/dev/null; then
  if nc -z 127.0.0.1 5432 2>/dev/null; then
    ok "Puerto 5432 accesible en 127.0.0.1"
  else
    warn "Puerto 5432 aun no responde. Espera unos segundos y vuelve a probar."
  fi
fi

# =========================================================================
# 5. Prueba de conexion con psql dentro del pod
# =========================================================================
step "Probando conexion a postgres desde dentro del pod..."
PG_POD=$(kubectl get pod -n "$NS" -l app=postgres -o jsonpath="{.items[0].metadata.name}")
kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U appuser -d appdb -c "SELECT 'IngressRouteTCP OK' AS resultado;"
ok "Conexion verificada"

# =========================================================================
# 6. Instrucciones para hosts de Windows
# =========================================================================
step "Para usar postgres.local en lugar de localhost (solo una vez como Admin):"
echo ""
echo "  Ejecuta como Administrador:"
echo "    infra\scripts\add-postgres-host.ps1"
echo ""
echo "  O manualmente en PowerShell elevada:"
echo "    Add-Content C:\Windows\System32\drivers\etc\hosts '127.0.0.1 postgres.local'"
echo ""

# =========================================================================
# 7. Resumen
# =========================================================================
echo "============================================================"
echo "  PostgreSQL -- URLs de acceso"
echo "============================================================"
echo ""
echo "  EXTERNA (host / dev local):"
echo "  localhost:5432       <-- disponible YA sin config extra"
echo "  postgres.local:5432  <-- tras ejecutar add-postgres-host.ps1"
echo ""
echo "  JDBC (local dev):"
echo "  jdbc:postgresql://postgres.local:5432/appdb"
echo ""
echo "  INTERNA (dentro de Kubernetes / pods):"
echo "  jdbc:postgresql://postgres.infra.svc.cluster.local:5432/appdb"
echo ""
echo "  NodePort (alternativa sin Traefik):"
echo "  jdbc:postgresql://localhost:30532/appdb"
echo ""
echo "  User/password: POSTGRES_USER / POSTGRES_PASSWORD from infra/.env.local  |  DB: appdb"
echo "============================================================"
