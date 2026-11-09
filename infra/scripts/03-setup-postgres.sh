#!/usr/bin/env bash
# =============================================================================
# 03-setup-postgres.sh
# Despliega PostgreSQL 16 (Alpine) en el namespace 'infra' de k3s.
#
# Características:
#   - Imagen ligera: postgres:16-alpine  (~80 MB)
#   - Persistencia:  PersistentVolumeClaim de 1 Gi
#   - Credenciales:  almacenadas en un Secret de Kubernetes
#   - Acceso intern: ClusterIP en puerto 5432
#   - Acceso externo: NodePort 30532 (desde host / WSL)
#
# Requisitos: Rancher Desktop instalado, k3s corriendo, kubectl disponible
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
PG_NODEPORT="15432"           # puerto accesible desde fuera del cluster

step()  { echo -e "\n==> $*"; }
ok()    { echo "    [ok] $*"; }
warn()  { echo "    [!]  $*"; }
fail()  { echo "    [x]  $*"; exit 1; }

# =============================================================================
# 0. Requisitos
# =============================================================================
step "Verificando requisitos..."
for cmd in kubectl; do
  command -v "$cmd" &>/dev/null || fail "'$cmd' no encontrado en el PATH."
done
kubectl cluster-info &>/dev/null || fail "k3s no responde. Abre Rancher Desktop."
ok "kubectl y k3s disponibles"

# =============================================================================
# 1. Namespace
# =============================================================================
step "Creando namespace '$NS'..."
if kubectl get namespace "$NS" &>/dev/null; then
  warn "Namespace '$NS' ya existe, omitiendo."
else
  kubectl create namespace "$NS"
  ok "Namespace '$NS' creado"
fi

# =============================================================================
# 2. Secret con credenciales
# =============================================================================
step "Creando Secret 'postgres-credentials' en namespace '$NS'..."
if kubectl get secret postgres-credentials -n "$NS" &>/dev/null; then
  warn "Secret ya existe, omitiendo (no se sobreescribirán credenciales)."
else
  kubectl create secret generic postgres-credentials \
    --namespace "$NS" \
    --from-literal=POSTGRES_USER="$PG_USER" \
    --from-literal=POSTGRES_PASSWORD="$PG_PASSWORD" \
    --from-literal=POSTGRES_DB="$PG_DB"
  ok "Secret creado"
fi

# =============================================================================
# 3. PersistentVolumeClaim
# =============================================================================
step "Creando PersistentVolumeClaim 'postgres-pvc'..."
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
ok "PVC aplicado"

# =============================================================================
# 4. Deployment
# =============================================================================
step "Desplegando PostgreSQL ${PG_VERSION}..."
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
ok "Deployment aplicado"

# =============================================================================
# 5. Service (ClusterIP + NodePort)
# =============================================================================
step "Creando Service 'postgres'..."
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
ok "Service aplicado (ClusterIP:5432 / NodePort:${PG_NODEPORT})"

# =============================================================================
# 6. Esperar a que el pod esté Ready
# =============================================================================
step "Esperando a que el pod de PostgreSQL esté listo (máx. 120 s)..."
kubectl rollout status deployment/postgres -n "$NS" --timeout=120s
ok "PostgreSQL listo"

# =============================================================================
# 7. Prueba de conexión
# =============================================================================
step "Probando conexión con psql (dentro del pod)..."
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

ok "Conexión verificada correctamente"

# =============================================================================
# 8. Resumen
# =============================================================================
echo ""
echo "============================================================"
echo "  PostgreSQL desplegado en namespace '$NS'"
echo "============================================================"
echo "  Host interno (en-cluster) : postgres.${NS}.svc.cluster.local"
echo "  Puerto interno            : 5432"
echo "  Host externo (NodePort)   : localhost:${PG_NODEPORT}"
echo "  Base de datos             : ${PG_DB}"
echo "  Usuario                   : ${PG_USER}"
echo "  Contraseña                : ${PG_PASSWORD}"
echo "------------------------------------------------------------"
echo "  Conexión desde app (JDBC) :"
echo "  jdbc:postgresql://postgres.${NS}.svc.cluster.local:5432/${PG_DB}"
echo "  Conexión desde host       :"
  echo "  psql -h postgres.local -p ${PG_NODEPORT} -U ${PG_USER} -d ${PG_DB}"
  echo "  (requiere: 127.0.0.1 postgres.local en fichero hosts de Windows)"
echo "============================================================"
