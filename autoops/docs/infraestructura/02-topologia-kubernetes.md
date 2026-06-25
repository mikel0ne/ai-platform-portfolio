# 02 — Topología Kubernetes: namespace, secret y RBAC

Esta guía crea la base: el namespace, el secret del proveedor LLM y los permisos que necesita AutoOps para
**leer** el estado del clúster (su función de diagnóstico). El RBAC de solo lectura es la pieza de seguridad
central del proyecto.

## 1. Namespace

`k8s/00-namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: autoops
  labels:
    app.kubernetes.io/part-of: autoops
```

```powershell
kubectl apply -f k8s/00-namespace.yaml
```

## 2. Secret del proveedor LLM

Un único secret: la API key del proveedor LLM/embeddings. (Weaviate corre sin autenticación en local, así que
no necesita secret; y no hay base de datos relacional.)

> **Importante:** este fichero NO se commitea con valores reales. Convención: commitear
> `k8s/01-secrets.template.yaml` con placeholders y mantener `k8s/01-secrets.yaml` en `.gitignore`.

`k8s/01-secrets.template.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: autoops-llm-secret
  namespace: autoops
type: Opaque
stringData:
  # Implementación por defecto: Google AI Studio (Gemini).
  # Si se cambia de proveedor, añadir aquí su clave (p.ej. AZURE_OPENAI_API_KEY)
  # y ajustar autoops.llm.provider en la config de la app.
  GEMINI_API_KEY: "<CAMBIAR-api-key-de-aistudio>"
```

Creación por línea de comandos (alternativa sin fichero):

```powershell
kubectl create secret generic autoops-llm-secret -n autoops `
  --from-literal=GEMINI_API_KEY="$env:GEMINI_API_KEY"
```

## 3. RBAC de AutoOps (lectura del clúster)

Las tools de diagnóstico (`diagnosticar_pod`, `logs_pod`, `eventos`…) consultan la API de Kubernetes desde
dentro del clúster. El pod corre con un `ServiceAccount` propio con permisos **estrictamente de lectura**.
Principio de mínimo privilegio: AutoOps **nunca** modifica el clúster ni lee Secrets.

`k8s/02-rbac.yaml`:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: autoops-mcp
  namespace: autoops
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: autoops-mcp-readonly
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

```powershell
kubectl apply -f k8s/02-rbac.yaml
```

## 4. Verificación

```powershell
kubectl get ns autoops
kubectl get secret autoops-llm-secret -n autoops
kubectl get sa,clusterrole,clusterrolebinding | Select-String autoops

# AutoOps puede LEER pods/logs pero NO escribir ni leer Secrets:
kubectl auth can-i list pods   --as=system:serviceaccount:autoops:autoops-mcp -A   # yes
kubectl auth can-i get pods/log --as=system:serviceaccount:autoops:autoops-mcp -A  # yes
kubectl auth can-i get secrets --as=system:serviceaccount:autoops:autoops-mcp -A   # no
kubectl auth can-i delete pods --as=system:serviceaccount:autoops:autoops-mcp -A   # no
```

Que las dos últimas devuelvan **no** es la garantía de seguridad del proyecto.

Continuar con [03-weaviate.md](03-weaviate.md).
