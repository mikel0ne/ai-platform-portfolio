#!/usr/bin/env bash
# =============================================================================
# 08-setup-wikijs.sh
# Despliega Wiki.js en el namespace 'wiki', vía Helm con el chart genérico
# infra/charts/generic-app (NO usa deploy.sh: imagen pública requarks/wiki:2,
# sin build/push propio, un solo componente — ver infra/values-wikijs.yaml).
#
# Requisito previo: wiki/scripts/setup-wiki-db.sh (crea la BD `wiki` vacía en
# el Postgres compartido del namespace `infra`; Wiki.js migra su propio
# esquema al arrancar).
#
# Tras este script: abrir http://wiki.local, completar el wizard inicial
# (usuario admin) y, en Admin Area → API Access, generar una API key con
# permisos read:pages + read:source (lectura, la usa el backend de AutoOps
# para la ingesta RAG) + write:pages (la usa wiki/scripts/seed-wiki-content.sh
# para publicar el contenido versionado en git).
#
# Requisitos: Rancher Desktop / k3s corriendo, kubectl y helm en el PATH.
# =============================================================================

set -euo pipefail

NS="wiki"
INFRA_NS="infra"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHART_DIR="${INFRA_DIR}/charts/generic-app"
VALUES="${INFRA_DIR}/values-wikijs.yaml"
RELEASE="wikijs"
DEPLOYMENT="${RELEASE}-generic-app"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requisitos
# =============================================================================
step "Verificando requisitos..."
command -v kubectl &>/dev/null || fail "'kubectl' no encontrado en el PATH."
command -v helm &>/dev/null || fail "'helm' no encontrado en el PATH."
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
[[ -f "$VALUES" ]] || fail "No encuentro el values: $VALUES"
kubectl get secret postgres-credentials -n "$INFRA_NS" &>/dev/null || \
  fail "No existe el Secret 'postgres-credentials' en '$INFRA_NS'. Ejecuta primero infra/scripts/03-setup-postgres.sh."
ok "kubectl, helm y k3s disponibles"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Asegurando namespace '$NS'..."
kubectl apply -f "${INFRA_DIR}/namespace-wiki.yaml" >/dev/null
ok "Namespace '$NS' listo"

# =============================================================================
# 2. Secret postgres-credentials (copiado del namespace infra, mismo appuser)
# =============================================================================
step "Sincronizando Secret 'postgres-credentials' en '$NS' (desde '$INFRA_NS')..."
PG_USER=$(kubectl get secret postgres-credentials -n "$INFRA_NS" -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)
PG_PASSWORD=$(kubectl get secret postgres-credentials -n "$INFRA_NS" -o jsonpath='{.data.POSTGRES_PASSWORD}' | base64 -d)
kubectl create secret generic postgres-credentials \
  --namespace "$NS" \
  --dry-run=client -o yaml \
  --from-literal=POSTGRES_USER="$PG_USER" \
  --from-literal=POSTGRES_PASSWORD="$PG_PASSWORD" \
| kubectl apply -f - >/dev/null
ok "Secret sincronizado"

# =============================================================================
# 3. Helm upgrade --install
# =============================================================================
step "helm upgrade --install ${RELEASE}..."
# --force-conflicts: Helm 4 usa server-side apply; necesario si un kubectl
# manual tocó antes el mismo recurso (mismo criterio que deploy.sh).
helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NS}" \
  -f "${VALUES}" \
  --force-conflicts \
  --wait --timeout 3m
ok "Release '${RELEASE}' desplegado"

# =============================================================================
# 4. Esperar rollout y comprobar /healthz
# =============================================================================
step "Esperando a que el pod esté Ready (máx. 120 s)..."
kubectl rollout status "deployment/${DEPLOYMENT}" -n "$NS" --timeout=120s
ok "Wiki.js Ready"

step "Comprobando /healthz dentro del pod..."
WIKI_POD=$(kubectl get pod -n "$NS" -l "app.kubernetes.io/instance=${RELEASE}" -o jsonpath="{.items[0].metadata.name}")
if kubectl exec -n "$NS" "$WIKI_POD" -- node -e "require('http').get('http://localhost:3000/healthz', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))" &>/dev/null; then
  ok "Endpoint /healthz responde"
else
  warn "/healthz no respondió 200; si el contenedor sigue arrancando, reintenta en unos segundos."
fi

# =============================================================================
# 5. Resumen
# =============================================================================
echo ""
echo "============================================================"
echo "  Wiki.js desplegado en namespace '$NS'"
echo "============================================================"
echo "  Host interno (en-cluster) : ${DEPLOYMENT}.${NS}.svc.cluster.local:3000"
echo "  BD                        : wiki (Postgres compartido, ns ${INFRA_NS})"
echo "------------------------------------------------------------"
echo "  Próximos pasos:"
echo "  1. infra/scripts/add-wiki-host.ps1   (Admin, una vez)"
echo "  2. Abrir http://wiki.local y completar el wizard inicial"
echo "  3. Admin Area → API Access → activar API y generar key"
echo "     (permisos read:pages + read:source + write:pages)"
echo "  4. Guardar la key en infra/.env.autoops.local como WIKIJS_API_KEY=..."
echo "============================================================"
