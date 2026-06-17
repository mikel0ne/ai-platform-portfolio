#!/usr/bin/env bash
# =============================================================================
# 06-setup-app-ingress.sh
# Configura el Ingress HTTP para la aplicacion L1 Copilot.
#
# Resultado tras ejecutarlo:
#   http://l1-copilot.local/          -> Frontend Angular
#   http://l1-copilot.local/api       -> Backend REST API
#   http://l1-copilot.local/actuator  -> Backend Actuator (health, info...)
#
# Como funciona:
#   Traefik ya expone el entrypoint HTTP "web" en el puerto 80 de localhost.
#   Un unico Ingress en el namespace l1-copilot enruta por path:
#     /api -> l1-copilot-backend-generic-app:8080  (el back monta /api via
#                                                    server.servlet.context-path,
#                                                    incluido el actuator)
#     /    -> l1-copilot-frontend-generic-app:80
#
#   l1-copilot.local se resuelve a 127.0.0.1 anadiendo una entrada al fichero
#   hosts de Windows. Ejecuta add-copilot-host.ps1 como Administrador (una vez).
#
# Requiere:
#   - k3s / Rancher Desktop corriendo
#   - Namespace l1-copilot creado (kubectl apply -f infra/namespace-l1-copilot.yaml)
#   - Servicios l1-copilot-backend y l1-copilot-frontend desplegados
#
# No requiere ningun cambio en Traefik ni en el fichero daemon.json de Docker.
# =============================================================================

set -euo pipefail

NS="l1-copilot"
# Los Services los crea el chart Helm `generic-app` con nombre <release>-generic-app
# (ver infra/charts/generic-app/templates/_helpers.tpl y deploy.sh RELEASE_*).
BACKEND_SVC="l1-copilot-backend-generic-app"
FRONTEND_SVC="l1-copilot-frontend-generic-app"
HOST="l1-copilot.local"

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
  warn "Servicio '$FRONTEND_SVC' no encontrado. El path '/' quedara sin backend hasta desplegar el frontend."

ok "Prerrequisitos OK"

# =========================================================================
# 1. Ingress unico con routing por path
# =========================================================================
step "Aplicando Ingress en ${HOST}..."
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: l1-copilot-app
  namespace: ${NS}
  labels:
    app.kubernetes.io/part-of: l1-copilot
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  ingressClassName: traefik
  rules:
    - host: "${HOST}"
      http:
        paths:
          # Backend -- todo bajo /api (incluye actuator, swagger, etc.)
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: ${BACKEND_SVC}
                port:
                  name: http
          # Frontend -- todo lo demas
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
# 2. Estado
# =========================================================================
step "Ingress activos en namespace '${NS}'..."
kubectl get ingress -n "$NS"

# =========================================================================
# 3. Prueba de conectividad
# =========================================================================
step "Probando conectividad (puede tardar unos segundos)..."
sleep 3

check_url() {
  local url="$1"
  if curl -sf --max-time 5 "$url" &>/dev/null; then
    ok "$url"
  else
    warn "$url -- sin respuesta aun (espera unos segundos y reintenta)"
  fi
}

check_url "http://${HOST}/api/actuator/health"
check_url "http://${HOST}/"

# =========================================================================
# 4. Instrucciones para el fichero hosts
# =========================================================================
step "Para usar ${HOST} en lugar de localhost (solo una vez como Admin):"
echo ""
echo "  Ejecuta como Administrador:"
echo "    infra\scripts\add-copilot-host.ps1"
echo ""
echo "  O manualmente en PowerShell elevada:"
echo "    Add-Content C:\Windows\System32\drivers\etc\hosts '127.0.0.1 ${HOST}'"
echo ""

# =========================================================================
# 5. Resumen
# =========================================================================
echo "============================================================"
echo "  L1 Copilot -- URLs de acceso"
echo "============================================================"
echo ""
echo "  http://${HOST}/                        <- Angular frontend"
echo "  http://${HOST}/api                     <- REST API"
echo "  http://${HOST}/api/actuator/health     <- Health check"
echo "  http://${HOST}/api/swagger-ui/index.html  <- Swagger UI"
echo ""
echo "  Puerto: 80 (HTTP estandar, sin configuracion extra en Traefik)"
echo "============================================================"
