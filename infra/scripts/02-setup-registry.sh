#!/usr/bin/env bash
# =============================================================================
# 02-setup-registry.sh
# Despliega un registry local en k3s accesible en localhost:30500
# Requiere: Rancher Desktop instalado y k3s corriendo
# =============================================================================

set -euo pipefail

REGISTRY_NAMESPACE="registry"
REGISTRY_PORT="30500"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 1. Requisitos
# -----------------------------------------------------------------------------
step "Verificando requisitos..."

for cmd in kubectl docker; do
  command -v "$cmd" &>/dev/null || fail "$cmd no encontrado en el PATH."
done
ok "kubectl y docker disponibles"

# jq (necesario para editar daemon.json)
if ! command -v jq &>/dev/null; then
  warn "jq no encontrado, instalando..."
  sudo apt-get install -y jq -qq
fi

kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop y espera a que el clúster esté verde."
ok "k3s operativo"

# -----------------------------------------------------------------------------
# 2. Namespace
# -----------------------------------------------------------------------------
step "Creando namespace '$REGISTRY_NAMESPACE'..."

if kubectl get namespace "$REGISTRY_NAMESPACE" &>/dev/null; then
  warn "Namespace '$REGISTRY_NAMESPACE' ya existe, omitiendo."
else
  kubectl create namespace "$REGISTRY_NAMESPACE"
  ok "Namespace creado"
fi

# -----------------------------------------------------------------------------
# 3. Manifiesto
# -----------------------------------------------------------------------------
step "Desplegando registry en k3s..."

kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: registry
  namespace: ${REGISTRY_NAMESPACE}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: registry
  template:
    metadata:
      labels:
        app: registry
    spec:
      containers:
        - name: registry
          image: registry:2
          ports:
            - containerPort: 5000
          env:
            - name: REGISTRY_STORAGE_DELETE_ENABLED
              value: "true"
          volumeMounts:
            - name: data
              mountPath: /var/lib/registry
      volumes:
        - name: data
          hostPath:
            path: /tmp/registry-data
            type: DirectoryOrCreate
---
apiVersion: v1
kind: Service
metadata:
  name: registry
  namespace: ${REGISTRY_NAMESPACE}
spec:
  selector:
    app: registry
  type: NodePort
  ports:
    - protocol: TCP
      port: 5000
      targetPort: 5000
      nodePort: ${REGISTRY_PORT}
EOF

ok "Manifiesto aplicado"

# -----------------------------------------------------------------------------
# 4. Configurar docker para registry inseguro
# -----------------------------------------------------------------------------
step "Configurando Docker para registry inseguro..."

daemon_json="${HOME}/.docker/daemon.json"
mkdir -p "$(dirname "$daemon_json")"

if [ ! -f "$daemon_json" ]; then
  echo '{}' > "$daemon_json"
fi

if ! grep -q "localhost:${REGISTRY_PORT}" "$daemon_json"; then
  tmp=$(mktemp)
  jq --arg r "localhost:${REGISTRY_PORT}" \
    '."insecure-registries" = (."insecure-registries" // [] | . + [$r] | unique)' \
    "$daemon_json" > "$tmp" && mv "$tmp" "$daemon_json"
  ok "daemon.json actualizado"
else
  warn "Registry ya configurado como inseguro, omitiendo."
fi

# -----------------------------------------------------------------------------
# 5. Esperar y verificar
# -----------------------------------------------------------------------------
step "Esperando a que el registry esté listo..."

kubectl rollout status deployment/registry -n "$REGISTRY_NAMESPACE" --timeout=120s
ok "Registry listo"

sleep 2

if curl -sf "http://localhost:${REGISTRY_PORT}/v2/" &>/dev/null; then
  ok "Registry responde en http://localhost:${REGISTRY_PORT}/v2/"
else
  warn "El registry aún no responde via HTTP. Prueba en unos segundos: curl http://localhost:${REGISTRY_PORT}/v2/"
fi

# -----------------------------------------------------------------------------
# 6. Resumen
# -----------------------------------------------------------------------------
cat <<EOF

  Registry local desplegado.

  Uso:
    docker tag  mi-imagen:tag  localhost:${REGISTRY_PORT}/mi-imagen:tag
    docker push localhost:${REGISTRY_PORT}/mi-imagen:tag

  Desde dentro del clúster:
    image: registry.registry.svc.cluster.local:5000/mi-imagen:tag

EOF
