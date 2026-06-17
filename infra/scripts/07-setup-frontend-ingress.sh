#!/usr/bin/env bash
# DEPRECATED: el routing de frontend esta integrado en 06-setup-app-ingress.sh
# Ejecuta ese script en su lugar.
exec "$(dirname "$0")/06-setup-app-ingress.sh" "$@"
# =============================================================================
# 07-setup-frontend-ingress.sh  [DEPRECATED -> 06-setup-app-ingress.sh]
# Configura un Ingress HTTP dedicado al frontend Angular.
#
# Resultado tras ejecutarlo:
#   copilot.localtest.me           -- App completa (gestionado por 06-setup-backend-ingress.sh)
#   frontend.localtest.me          -- Frontend aislado (dev: recarga, tests visuales...)
#
# Como funciona:
#   *.localtest.me resuelve publicamente a 127.0.0.1 -- no hay que tocar el
#   fichero hosts. Se crea un Ingress adicional de solo frontend que apunta
#   directamente al servicio l1-copilot-frontend, util para desarrollar la
#   interfaz sin depender del backend.
#
# Requiere:
#   - 06-setup-backend-ingress.sh ejecutado previamente (Ingress principal)
#   - Servicio l1-copilot-frontend desplegado
# =============================================================================

set -euo pipefail

NS="l1-copilot"
FRONTEND_SVC="l1-copilot-frontend"
BACKEND_SVC="l1-copilot-backend"
APP_HOST="copilot.localtest.me"
FE_DEV_HOST="frontend.localtest.me"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =========================================================================
# 0. Requisitos
# =========================================================================
step "Verificando requisitos..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."

kubectl get namespace "$NS" &>/dev/null || \
  fail "Namespace '$NS' no existe. Ejecuta: kubectl apply -f infra/namespace-l1-copilot.yaml"

kubectl get svc "$FRONTEND_SVC" -n "$NS" &>/dev/null || \
  fail "Servicio '$FRONTEND_SVC' no encontrado en namespace '$NS'. Despliega el frontend primero."

# Verificar que el Ingress principal (del backend) existe
if kubectl get ingress l1-copilot-app -n "$NS" &>/dev/null; then
  ok "Ingress principal 'l1-copilot-app' ya existe"
else
  warn "Ingress principal no encontrado. Ejecuta primero 06-setup-backend-ingress.sh"
fi

ok "Prerrequisitos OK"

# =========================================================================
# 1. Ingress dev frontend -- frontend.localtest.me
#    Apunta directamente al servicio frontend.
#    Las llamadas /api/* del frontend Angular deben ir al backend por separado;
#    aqui se expone el SPA puro para desarrollar UI con hot-reload / mocks.
# =========================================================================
step "Creando Ingress dev frontend: ${FE_DEV_HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: l1-copilot-frontend-dev
  namespace: ${NS}
  labels:
    app.kubernetes.io/part-of: l1-copilot
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${FE_DEV_HOST}"
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${FRONTEND_SVC}
                port:
                  name: http
          # Redirige /api al backend desde el mismo host de dev frontend
          # util si el proxy.conf.json del dev-server ya no esta activo
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
          - path: /actuator/health
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
EOF
ok "Ingress 'l1-copilot-frontend-dev' aplicado"

# =========================================================================
# 2. Verificar que los Ingress estan activos
# =========================================================================
step "Estado de los Ingress en namespace '${NS}'..."
kubectl get ingress -n "$NS"

# =========================================================================
# 3. Prueba de conectividad HTTP
# =========================================================================
step "Probando conectividad..."
sleep 3

check_url() {
  local url="$1"
  local label="$2"
  if curl -sf --max-time 5 "$url" &>/dev/null; then
    ok "$label -> OK"
  else
    warn "$label -> sin respuesta aun (puede tardar unos segundos tras el primer despliegue)"
  fi
}

check_url "http://${APP_HOST}/"         "http://${APP_HOST}/ (app completa)"
check_url "http://${FE_DEV_HOST}/"      "http://${FE_DEV_HOST}/ (frontend dev)"

# =========================================================================
# 4. Resumen
# =========================================================================
echo ""
echo "============================================================"
echo "  L1 Copilot Frontend -- URLs de acceso"
echo "============================================================"
echo ""
echo "  APP COMPLETA (frontend + backend en un solo host):"
echo "  http://${APP_HOST}/          <- Angular app"
echo "  http://${APP_HOST}/api       <- REST API (mismo host)"
echo ""
echo "  DEV FRONTEND (aislado, con proxy /api al backend):"
echo "  http://${FE_DEV_HOST}/       <- SPA Angular"
echo "  http://${FE_DEV_HOST}/api    <- proxy a ${BACKEND_SVC}"
echo ""
echo "  NOTA: *.localtest.me resuelve a 127.0.0.1 sin tocar el hosts."
echo ""
echo "  Si desarrollas con 'ng serve', el proxy.conf.json ya redirige"
echo "  /api a localhost:8080. Este Ingress es para la imagen en k8s."
echo "============================================================"
