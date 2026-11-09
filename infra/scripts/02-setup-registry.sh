#!/usr/bin/env bash
# =============================================================================
# 02-setup-registry.sh
# Deploys a local registry in k3s, reachable at localhost:30500.
# Requires Rancher Desktop with k3s running.
# =============================================================================

set -euo pipefail

REGISTRY_NAMESPACE="registry"
REGISTRY_PORT="30500"

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# -----------------------------------------------------------------------------
# 1. Requirements
# -----------------------------------------------------------------------------
step "Checking requirements..."

for cmd in kubectl docker; do
  command -v "$cmd" &>/dev/null || fail "$cmd not found on PATH."
done
ok "kubectl and docker available"

# jq (needed to edit daemon.json)
if ! command -v jq &>/dev/null; then
  warn "jq not found, installing..."
  sudo apt-get install -y jq -qq
fi

kubectl cluster-info &>/dev/null || fail "k3s is not responding. Open Rancher Desktop and wait for the cluster to turn green."
ok "k3s up"

# -----------------------------------------------------------------------------
# 2. Namespace
# -----------------------------------------------------------------------------
step "Creating namespace '$REGISTRY_NAMESPACE'..."

if kubectl get namespace "$REGISTRY_NAMESPACE" &>/dev/null; then
  warn "Namespace '$REGISTRY_NAMESPACE' already exists, skipping."
else
  kubectl create namespace "$REGISTRY_NAMESPACE"
  ok "Namespace created"
fi

# -----------------------------------------------------------------------------
# 3. Manifest
# -----------------------------------------------------------------------------
step "Deploying the registry to k3s..."

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

ok "Manifest applied"

# -----------------------------------------------------------------------------
# 4. Configure Docker for an insecure registry
# -----------------------------------------------------------------------------
step "Configuring Docker for an insecure registry..."

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
  ok "daemon.json updated"
else
  warn "Registry already configured as insecure, skipping."
fi

# -----------------------------------------------------------------------------
# 5. Wait and verify
# -----------------------------------------------------------------------------
step "Waiting for the registry to be ready..."

kubectl rollout status deployment/registry -n "$REGISTRY_NAMESPACE" --timeout=120s
ok "Registry ready"

sleep 2

if curl -sf "http://localhost:${REGISTRY_PORT}/v2/" &>/dev/null; then
  ok "Registry answers at http://localhost:${REGISTRY_PORT}/v2/"
else
  warn "The registry does not answer over HTTP yet. Retry in a few seconds: curl http://localhost:${REGISTRY_PORT}/v2/"
fi

# -----------------------------------------------------------------------------
# 6. Summary
# -----------------------------------------------------------------------------
cat <<EOF

  Local registry deployed.

  Usage:
    docker tag  my-image:tag  localhost:${REGISTRY_PORT}/my-image:tag
    docker push localhost:${REGISTRY_PORT}/my-image:tag

  From inside the cluster:
    image: registry.registry.svc.cluster.local:5000/my-image:tag

EOF
