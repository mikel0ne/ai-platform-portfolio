#!/usr/bin/env bash
# =============================================================================
# 05-setup-registry-ingress.sh
# Configura acceso externo al registry con URL limpia desde el host.
#
# Resultado tras ejecutarlo:
#   localhost:30500       -- INMEDIATO (NodePort existente)
#   localhost:15000       -- via Traefik TCP entrypoint
#   registry.local:15000  -- tras ejecutar add-registry-host.ps1 como Administrador
#
# Como funciona:
#   Traefik (LoadBalancer en k3s/Rancher Desktop) expone el entrypoint TCP
#   registry en localhost:15000 a traves del Klipper LB de k3s.
#   El IngressRouteTCP enruta ese trafico al servicio registry en namespace registry.
#
# Requiere: script 02-setup-registry.sh ejecutado previamente
# =============================================================================

set -euo pipefail

NS="registry"
REGISTRY_PORT="15000"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =========================================================================
# 0. Requisitos
# =========================================================================
step "Verificando requisitos..."
kubectl cluster-info &>/dev/null || fail "k3s no responde."
kubectl get svc registry -n "$NS" &>/dev/null || \
  fail "Servicio registry no encontrado en namespace '$NS'. Ejecuta primero 02-setup-registry.sh"
ok "Prerrequisitos OK"

# =========================================================================
# 1. HelmChartConfig -- entrypoint TCP registry en Traefik puerto 15000
# =========================================================================
step "Anadiendo entrypoint TCP registry (${REGISTRY_PORT}) a Traefik..."
kubectl apply -f - <<EOF
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
      registry:
        port: ${REGISTRY_PORT}
        expose:
          default: true
        exposedPort: ${REGISTRY_PORT}
        protocol: TCP
EOF
ok "HelmChartConfig aplicado"

# =========================================================================
# 2. Esperar a que Traefik se reinicie con el nuevo entrypoint
# =========================================================================
step "Esperando a que Traefik aplique la nueva configuracion (max. 90 s)..."
kubectl rollout status deployment/traefik -n kube-system --timeout=90s
ok "Traefik listo"

# =========================================================================
# 3. IngressRouteTCP
# =========================================================================
step "Creando IngressRouteTCP registry..."
kubectl apply -f - <<'EOF'
apiVersion: traefik.io/v1alpha1
kind: IngressRouteTCP
metadata:
  name: registry
  namespace: registry
spec:
  entryPoints:
    - registry
  routes:
    - match: HostSNI(*)
      services:
        - name: registry
          port: 5000
EOF
ok "IngressRouteTCP creado"

# =========================================================================
# 4. Verificar que el puerto 15000 esta expuesto en localhost
# =========================================================================
step "Verificando que localhost:${REGISTRY_PORT} es accesible..."
sleep 5
if command -v nc &>/dev/null; then
  if nc -z 127.0.0.1 "${REGISTRY_PORT}" 2>/dev/null; then
    ok "Puerto ${REGISTRY_PORT} accesible en 127.0.0.1"
  else
    warn "Puerto ${REGISTRY_PORT} aun no responde. Espera unos segundos y vuelve a probar."
  fi
fi

# =========================================================================
# 5. Prueba de conexion HTTP con el registry
# =========================================================================
step "Probando endpoint /v2/ del registry..."
sleep 3
if curl -sf "http://localhost:${REGISTRY_PORT}/v2/" &>/dev/null; then
  ok "Registry responde en http://localhost:${REGISTRY_PORT}/v2/"
else
  warn "El registry aun no responde en el puerto ${REGISTRY_PORT}. Prueba en unos segundos:"
  warn "  curl http://localhost:${REGISTRY_PORT}/v2/"
fi

# =========================================================================
# 6. Actualizar daemon.json de Docker para incluir registry.local:15000
# =========================================================================
step "Actualizando daemon.json de Docker para registry.local:${REGISTRY_PORT}..."

daemon_json="${HOME}/.docker/daemon.json"
mkdir -p "$(dirname "$daemon_json")"
[ ! -f "$daemon_json" ] && echo '{}' > "$daemon_json"

if command -v jq &>/dev/null; then
  for entry in "localhost:${REGISTRY_PORT}" "registry.local:${REGISTRY_PORT}"; do
    if ! grep -q "$entry" "$daemon_json"; then
      tmp=$(mktemp)
      jq --arg r "$entry" \
        '."insecure-registries" = (."insecure-registries" // [] | . + [$r] | unique)' \
        "$daemon_json" > "$tmp" && mv "$tmp" "$daemon_json"
      ok "Anadido a insecure-registries: $entry"
    else
      warn "Ya existe en insecure-registries: $entry"
    fi
  done
else
  warn "jq no disponible -- anade manualmente a daemon.json:"
  warn '  "insecure-registries": ["localhost:'"${REGISTRY_PORT}"'", "registry.local:'"${REGISTRY_PORT}"'"]'
fi

# =========================================================================
# 7. Instrucciones para hosts de Windows
# =========================================================================
step "Para usar registry.local en lugar de localhost (solo una vez como Admin):"
echo ""
echo "  Ejecuta como Administrador:"
echo "    scripts\add-registry-host.ps1"
echo ""
echo "  O manualmente en PowerShell elevada:"
echo "    Add-Content C:\Windows\System32\drivers\etc\hosts '127.0.0.1 registry.local'"
echo ""

# =========================================================================
# 8. Resumen
# =========================================================================
echo "============================================================"
echo "  Registry local -- URLs de acceso"
echo "============================================================"
echo ""
echo "  EXTERNA (host / dev local):"
echo "  localhost:30500         <-- NodePort directo (sin Traefik)"
echo "  localhost:${REGISTRY_PORT}         <-- via Traefik TCP"
echo "  registry.local:${REGISTRY_PORT}    <-- tras ejecutar add-registry-host.ps1"
echo ""
echo "  Uso con Docker:"
echo "  docker tag  mi-imagen:tag  registry.local:${REGISTRY_PORT}/mi-imagen:tag"
echo "  docker push registry.local:${REGISTRY_PORT}/mi-imagen:tag"
echo ""
echo "  INTERNA (dentro de Kubernetes / pods):"
echo "  registry.registry.svc.cluster.local:5000"
echo "============================================================"
