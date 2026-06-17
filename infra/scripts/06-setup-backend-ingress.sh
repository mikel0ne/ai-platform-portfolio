#!/usr/bin/env bash
# DEPRECATED: sustituido por 06-setup-app-ingress.sh
# Ejecuta ese script en su lugar.
exec "$(dirname "$0")/06-setup-app-ingress.sh" "$@"
# =============================================================================
# 06-setup-backend-ingress.sh  [DEPRECATED -> 06-setup-app-ingress.sh]
# Configura el Ingress HTTP del backend (y del frontend como extra path).
#
# Resultado tras ejecutarlo:
#   copilot.localtest.me           -- App completa (front + /api + /actuator/health)
#   backend.localtest.me           -- Acceso directo al backend (dev: Swagger, actuator...)
#
# Como funciona:
#   *.localtest.me resuelve publicamente a 127.0.0.1 -- no hay que tocar el
#   fichero hosts. Traefik (IngressClass: traefik) enruta el trafico HTTP
#   entrante segun el hostname y el path.
#
#   Se crean dos recursos Ingress en el namespace l1-copilot:
#     1. l1-copilot-app    -> host copilot.localtest.me  (app completa)
#     2. l1-copilot-backend-dev -> host backend.localtest.me (dev directo al API)
#
# Requiere:
#   - k3s / Rancher Desktop corriendo
#   - Namespace l1-copilot creado (kubectl apply -f infra/namespace-l1-copilot.yaml)
#   - Servicios l1-copilot-backend y l1-copilot-frontend desplegados
# =============================================================================

set -euo pipefail

NS="l1-copilot"
BACKEND_SVC="l1-copilot-backend"
FRONTEND_SVC="l1-copilot-frontend"
APP_HOST="copilot.localtest.me"
DEV_HOST="backend.localtest.me"

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

kubectl get svc "$BACKEND_SVC" -n "$NS" &>/dev/null || \
  fail "Servicio '$BACKEND_SVC' no encontrado en namespace '$NS'. Despliega el backend primero."

kubectl get svc "$FRONTEND_SVC" -n "$NS" &>/dev/null || \
  warn "Servicio '$FRONTEND_SVC' no encontrado. El path '/' del app ingress quedara sin backend hasta que se despliegue el frontend."

ok "Prerrequisitos OK"

# =========================================================================
# 1. Ingress principal -- copilot.localtest.me
#    /api             -> l1-copilot-backend:http
#    /actuator/health -> l1-copilot-backend:http
#    /                -> l1-copilot-frontend:http
# =========================================================================
step "Creando Ingress principal: ${APP_HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: l1-copilot-app
  namespace: ${NS}
  labels:
    app.kubernetes.io/part-of: l1-copilot
  annotations:
    # Redirige HTTP -> HTTPS si el entorno tiene TLS; en local se ignora
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${APP_HOST}"
      http:
        paths:
          # Backend: API REST
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
          # Backend: health endpoint para LB / monitoring
          - path: /actuator/health
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
          # Frontend: todo lo demas
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${FRONTEND_SVC}
                port:
                  name: http
EOF
ok "Ingress 'l1-copilot-app' aplicado"

# =========================================================================
# 2. Ingress dev backend -- backend.localtest.me
#    /  -> l1-copilot-backend:http  (expone TODO: Swagger, actuator, debug)
# =========================================================================
step "Creando Ingress dev backend: ${DEV_HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: l1-copilot-backend-dev
  namespace: ${NS}
  labels:
    app.kubernetes.io/part-of: l1-copilot
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${DEV_HOST}"
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
EOF
ok "Ingress 'l1-copilot-backend-dev' aplicado"

# =========================================================================
# 3. Verificar que los Ingress estan activos
# =========================================================================
step "Estado de los Ingress en namespace '${NS}'..."
kubectl get ingress -n "$NS"

# =========================================================================
# 4. Prueba de conectividad HTTP
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

check_url "http://${APP_HOST}/actuator/health" "http://${APP_HOST}/actuator/health"
check_url "http://${DEV_HOST}/actuator/health" "http://${DEV_HOST}/actuator/health"

# =========================================================================
# 5. Resumen
# =========================================================================
echo ""
echo "============================================================"
echo "  L1 Copilot Backend -- URLs de acceso"
echo "============================================================"
echo ""
echo "  APP COMPLETA (front + back):"
echo "  http://${APP_HOST}                 <- Angular app"
echo "  http://${APP_HOST}/api             <- REST API"
echo "  http://${APP_HOST}/actuator/health <- Health check"
echo ""
echo "  DEV (acceso directo al backend, sin frontend):"
echo "  http://${DEV_HOST}/                         <- root"
echo "  http://${DEV_HOST}/swagger-ui/index.html    <- Swagger UI"
echo "  http://${DEV_HOST}/actuator                 <- Actuator endpoints"
echo "  http://${DEV_HOST}/actuator/health          <- Health"
echo "  http://${DEV_HOST}/api/...                  <- API directa"
echo ""
echo "  NOTA: *.localtest.me resuelve a 127.0.0.1 sin tocar el hosts."
echo "============================================================"
