#!/usr/bin/env bash
# =============================================================================
# 03-setup-postgres.sh
# Deploys PostgreSQL 16 (Alpine) to the 'infra' namespace of k3s.
#
# Features:
#   - Small image:     postgres:16-alpine (~80 MB)
#   - Persistence:     1 Gi PersistentVolumeClaim
#   - Credentials:     stored in a Kubernetes Secret
#   - Internal access: ClusterIP on port 5432
#   - External access: NodePort (from the host / WSL)
#
# Requires Rancher Desktop with k3s running and kubectl.
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Variables. Credentials come from infra/.env.local (gitignored).
# -----------------------------------------------------------------------------
ENV_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env.local"
if [[ -f "$ENV_FILE" ]]; then
  set -a; source "$ENV_FILE"; set +a
fi
: "${POSTGRES_USER:?Define POSTGRES_USER in infra/.env.local (see infra/.env.local.example)}"
: "${POSTGRES_PASSWORD:?Define POSTGRES_PASSWORD in infra/.env.local (see infra/.env.local.example)}"

NS="infra"
PG_USER="$POSTGRES_USER"
PG_PASSWORD="$POSTGRES_PASSWORD"
PG_DB="appdb"
PG_VERSION="16-alpine"
PG_STORAGE="1Gi"
PG_NODEPORT="15432"           # reachable from outside the cluster

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requirements
# =============================================================================
step "Checking requirements..."
for cmd in kubectl; do
  command -v "$cmd" &>/dev/null || fail "'$cmd' not found on PATH."
done
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
ok "kubectl and k3s available"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Creating namespace '$NS'..."
if kubectl get namespace "$NS" &>/dev/null; then
  warn "Namespace '$NS' already exists, skipping."
else
  kubectl create namespace "$NS"
  ok "Namespace '$NS' created"
fi

# =============================================================================
# 2. Credentials Secret
# =============================================================================
step "Creating Secret 'postgres-credentials' in namespace '$NS'..."
if kubectl get secret postgres-credentials -n "$NS" &>/dev/null; then
  warn "Secret already exists, skipping (credentials are never overwritten)."
else
  # Through a private temp file: a --from-literal value would show up in the process list.
  creds_file="$(mktemp)"
  chmod 600 "$creds_file"
  printf 'POSTGRES_USER=%s\nPOSTGRES_PASSWORD=%s\nPOSTGRES_DB=%s\n' "$PG_USER" "$PG_PASSWORD" "$PG_DB" > "$creds_file"
  kubectl create secret generic postgres-credentials \
    --namespace "$NS" \
    --from-env-file="$creds_file"
  rm -f "$creds_file"
  ok "Secret created"
fi

# =============================================================================
# 3. PersistentVolumeClaim
# =============================================================================
step "Creating PersistentVolumeClaim 'postgres-pvc'..."
kubectl apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
  namespace: ${NS}
  labels:
    app: postgres
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: ${PG_STORAGE}
EOF
ok "PVC applied"

# =============================================================================
# 4. Deployment
# =============================================================================
step "Deploying PostgreSQL ${PG_VERSION}..."
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  namespace: ${NS}
  labels:
    app: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: postgres:${PG_VERSION}
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: postgres-credentials
          resources:
            requests:
              cpu: "100m"
              memory: "128Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "${PG_USER}", "-d", "${PG_DB}"]
            initialDelaySeconds: 10
            periodSeconds: 5
          livenessProbe:
            exec:
              command: ["pg_isready", "-U", "${PG_USER}", "-d", "${PG_DB}"]
            initialDelaySeconds: 30
            periodSeconds: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: postgres-pvc
EOF
ok "Deployment applied"

# =============================================================================
# 5. Service (ClusterIP + NodePort)
# =============================================================================
step "Creating Service 'postgres'..."
kubectl apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: ${NS}
  labels:
    app: postgres
spec:
  type: NodePort
  selector:
    app: postgres
  ports:
    - name: postgresql
      protocol: TCP
      port: 5432
      targetPort: 5432
      nodePort: ${PG_NODEPORT}
EOF
ok "Service applied (ClusterIP:5432 / NodePort:${PG_NODEPORT})"

# =============================================================================
# 6. Wait until the pod is Ready
# =============================================================================
step "Waiting for the PostgreSQL pod to be ready (max. 120 s)..."
kubectl rollout status deployment/postgres -n "$NS" --timeout=120s
ok "PostgreSQL ready"

# =============================================================================
# 7. Connection test
# =============================================================================
step "Testing the connection with psql (inside the pod)..."
PG_POD=$(kubectl get pod -n "$NS" -l app=postgres -o jsonpath="{.items[0].metadata.name}")

kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U "$PG_USER" -d "$PG_DB" -c "\conninfo"

kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U "$PG_USER" -d "$PG_DB" -c "SELECT version();"

kubectl exec -n "$NS" "$PG_POD" -- \
  psql -U "$PG_USER" -d "$PG_DB" -c "
    CREATE TABLE IF NOT EXISTS healthcheck (
      id   SERIAL PRIMARY KEY,
      ts   TIMESTAMPTZ DEFAULT now(),
      note TEXT
    );
    INSERT INTO healthcheck(note) VALUES ('setup ok');
    SELECT * FROM healthcheck;
  "

ok "Connection verified"

# =============================================================================
# 8. Summary
# =============================================================================
echo ""
echo "============================================================"
echo "  PostgreSQL deployed to namespace '$NS'"
echo "============================================================"
echo "  Internal host (in-cluster) : postgres.${NS}.svc.cluster.local"
echo "  Internal port              : 5432"
echo "  External host (NodePort)   : localhost:${PG_NODEPORT}"
echo "  Database                   : ${PG_DB}"
echo "  User                       : ${PG_USER}"
echo "  Password                   : POSTGRES_PASSWORD in infra/.env.local"
echo "------------------------------------------------------------"
echo "  From an app (JDBC)         :"
echo "  jdbc:postgresql://postgres.${NS}.svc.cluster.local:5432/${PG_DB}"
echo "  From the host              :"
  echo "  psql -h postgres.local -p ${PG_NODEPORT} -U ${PG_USER} -d ${PG_DB}"
  echo "  (requires 127.0.0.1 postgres.local in the Windows hosts file)"
echo "============================================================"
