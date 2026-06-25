#!/usr/bin/env bash
# =============================================================================
# setup-app-ingress.sh — Ingress (Traefik) genérico para un proyecto del monorepo
# =============================================================================
# Script GENÉRICO. Lee infra/projects/<project>.env y crea UN Ingress que enruta
# por path hacia el backend y el frontend del proyecto:
#
#   <INGRESS_BACKEND_PATHS...>  -> <project>-backend-generic-app
#   /                           -> <project>-frontend-generic-app
#
# Traefik ya expone el entrypoint HTTP "web" en el puerto 80 de localhost (k3s /
# Rancher Desktop). No requiere tocar daemon.json ni la config de Traefik.
# Traefik NO bufferiza respuestas por defecto → el SSE del MCP (/sse) fluye sin
# configuración extra.
#
# Uso:
#   ./infra/scripts/setup-app-ingress.sh --project autoops
#   ./infra/scripts/setup-app-ingress.sh --project l1-copilot
#
# El host (p.ej. autoops.local) se resuelve a 127.0.0.1 con el fichero hosts de
# Windows: infra/scripts/add-<project>-host.ps1 (como Administrador, una vez).
# =============================================================================
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# ── Argumentos ───────────────────────────────────────────────────────────────
PROJECT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="${2:?--project requiere valor}"; shift 2 ;;
    -h|--help) sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) fail "Flag desconocido: $1" ;;
  esac
done
[[ -n "${PROJECT}" ]] || fail "Debes indicar --project <nombre>"

readonly PROJECT_ENV="${INFRA_DIR}/projects/${PROJECT}.env"
[[ -f "${PROJECT_ENV}" ]] || fail "No existe el descriptor ${PROJECT_ENV}"
# shellcheck disable=SC1090
source "${PROJECT_ENV}"

: "${NAMESPACE:?El descriptor debe definir NAMESPACE}"
: "${INGRESS_HOST:?El descriptor debe definir INGRESS_HOST}"
BACKEND_PATHS="${INGRESS_BACKEND_PATHS:-/api}"

# Los Services los crea el chart generic-app con nombre <release>-generic-app.
readonly BACKEND_SVC="${PROJECT}-backend-generic-app"
readonly FRONTEND_SVC="${PROJECT}-frontend-generic-app"

# ── Requisitos ───────────────────────────────────────────────────────────────
step "Verificando requisitos (${PROJECT})..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get namespace "$NAMESPACE" &>/dev/null || \
  fail "Namespace '$NAMESPACE' no existe. Despliega primero (deploy.sh)."
kubectl get svc "$BACKEND_SVC" -n "$NAMESPACE" &>/dev/null || \
  fail "Servicio '$BACKEND_SVC' no encontrado. Despliega el backend primero."
kubectl get svc "$FRONTEND_SVC" -n "$NAMESPACE" &>/dev/null || \
  warn "Servicio '$FRONTEND_SVC' no encontrado. '/' quedará sin backend hasta desplegar el frontend."
ok "Prerrequisitos OK"

# ── Construcción de las reglas de path del backend ──────────────────────────
backend_paths_yaml() {
  local p
  for p in ${BACKEND_PATHS}; do
    cat <<EOF
          - path: ${p}
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
EOF
  done
}

# ── Ingress ──────────────────────────────────────────────────────────────────
step "Aplicando Ingress en ${INGRESS_HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${PROJECT}-app
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/part-of: ${PROJECT}
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${INGRESS_HOST}"
      http:
        paths:
$(backend_paths_yaml)
          # Frontend — todo lo demás
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${FRONTEND_SVC}
                port:
                  name: http
EOF
ok "Ingress '${PROJECT}-app' aplicado"

step "Ingress en namespace '${NAMESPACE}':"
kubectl get ingress -n "$NAMESPACE"

step "Probando conectividad (puede tardar unos segundos)..."
sleep 3
check_url() {
  if curl -sf --max-time 5 "$1" &>/dev/null; then ok "$1"; else warn "$1 — sin respuesta aún"; fi
}
# Primera ruta de backend como sonda de salud
check_url "http://${INGRESS_HOST}/"

echo ""
echo "============================================================"
echo "  ${PROJECT} — URLs de acceso"
echo "============================================================"
echo "  http://${INGRESS_HOST}/                 <- Frontend"
for p in ${BACKEND_PATHS}; do
  echo "  http://${INGRESS_HOST}${p}    <- Backend"
done
echo ""
echo "  Si no resuelve el host: infra/scripts/add-host.ps1 -Hostname ${INGRESS_HOST} (Admin)"
echo "============================================================"
