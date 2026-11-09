# 02 — Topología Kubernetes: namespace, secret y RBAC

Esta guía describe la base: el namespace, el secret del proveedor LLM y los permisos que necesita AutoOps para
**leer** el estado del clúster (su función de diagnóstico). El RBAC de solo lectura es la pieza de seguridad
central del proyecto. **No hay una carpeta `k8s/`**: los manifiestos viven en `infra/` (convención
`namespace-<project>.yaml` / `rbac-<project>.yaml`) y `infra/scripts/deploy.sh --project autoops` los aplica
automáticamente — no se ejecuta `kubectl apply -f` a mano salvo para depurar.

## 1. Namespace

`infra/namespace-autoops.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: autoops
  labels:
    app.kubernetes.io/part-of: autoops
```

Lo aplica `deploy.sh` (función `ensure_namespace`) antes del primer `helm install`; aplicarlo a mano
(`kubectl apply -f infra/namespace-autoops.yaml`) es solo un atajo de depuración.

## 2. Secret del proveedor LLM

Un único secret: la API key del proveedor LLM/embeddings. (Weaviate corre sin autenticación en local, así que
no necesita secret; y no hay base de datos relacional.)

No hay un YAML de Secret en el repo (ni siquiera con placeholders): `deploy.sh` lo **sincroniza
automáticamente** (`sync_secrets`, idempotente vía `kubectl create secret ... --dry-run=client | kubectl
apply -f -`) a partir de las claves declaradas en `infra/projects/autoops.env`:

```bash
declare -gA SECRETS_BACKEND=(
  [autoops-llm-secret]="GEMINI_API_KEY"
)
```

El **valor real** de `GEMINI_API_KEY` no se commitea: vive en `infra/.env.local` (compartida por todos los
proyectos del portfolio) o en `infra/.env.autoops.local` (específica, gitignored). Si la variable está vacía,
`deploy.sh` avisa pero sigue (el Secret se crea con valor vacío, falla luego en readiness — guía 07).

## 3. RBAC de AutoOps (lectura del clúster)

Las tools de diagnóstico (`diagnosticar_pod`, `logs_pod`, `eventos`…) consultan la API de Kubernetes desde
dentro del clúster. El pod corre con un `ServiceAccount` propio con permisos **estrictamente de lectura**.
Principio de mínimo privilegio: AutoOps **nunca** modifica el clúster ni lee Secrets.

`infra/rbac-autoops.yaml` (aplicado por `deploy.sh` justo después del namespace; el values del backend lo
referencia por nombre con `serviceAccount: { create: false, name: autoops-mcp }` — el chart de Helm NO lo
crea, porque su ciclo de vida es cluster-scoped, fuera del release):

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: autoops-mcp
  namespace: autoops
  labels:
    app.kubernetes.io/part-of: autoops
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: autoops-mcp-readonly
  labels:
    app.kubernetes.io/part-of: autoops
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/log", "events", "services", "configmaps", "namespaces", "nodes"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets", "statefulsets", "daemonsets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["batch"]
    resources: ["jobs", "cronjobs"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: autoops-mcp-readonly
  labels:
    app.kubernetes.io/part-of: autoops
subjects:
  - kind: ServiceAccount
    name: autoops-mcp
    namespace: autoops
roleRef:
  kind: ClusterRole
  name: autoops-mcp-readonly
  apiGroup: rbac.authorization.k8s.io
```

> **Nota de diseño:** se usa `ClusterRole` (no `Role`) porque el caso de uso es diagnosticar workloads en
> *cualquier* namespace del clúster local. En un entorno corporativo real se restringiría con `RoleBinding` por
> namespace. Los **Secrets** quedan deliberadamente **fuera** de los recursos legibles.

## 4. Verificación

```bash
kubectl get ns autoops
kubectl get secret autoops-llm-secret -n autoops
kubectl get sa,clusterrole,clusterrolebinding | grep autoops

# AutoOps puede LEER pods/logs pero NO escribir ni leer Secrets:
kubectl auth can-i list pods    --as=system:serviceaccount:autoops:autoops-mcp -A   # yes
kubectl auth can-i get pods/log --as=system:serviceaccount:autoops:autoops-mcp -A   # yes
kubectl auth can-i get secrets  --as=system:serviceaccount:autoops:autoops-mcp -A   # no
kubectl auth can-i delete pods  --as=system:serviceaccount:autoops:autoops-mcp -A   # no
```

Que las dos últimas devuelvan **no** es la garantía de seguridad del proyecto.

> **Selectores de label:** los pods de AutoOps (y de cualquier otro proyecto que use el chart genérico) tienen
> `app.kubernetes.io/name=generic-app` (constante, no distingue nada) y `app.kubernetes.io/instance=autoops-backend`
> / `autoops-frontend` (lo que sí distingue cada release). Para filtrar con `-l`/`--selector` (a mano o desde la
> tool `estado_pods`) usar `instance`, no `name` ni `app=`.

Continuar con [03-weaviate.md](03-weaviate.md).
