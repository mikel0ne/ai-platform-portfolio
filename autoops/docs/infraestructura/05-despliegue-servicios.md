# 05 — Despliegue de los servicios

Despliegue de `autoops-backend` y `autoops-frontend`. Requiere completadas las guías 02 (namespace/secret/RBAC),
03 (Weaviate) y 04 (imágenes construidas).

## 1. Helm, no manifiestos sueltos

No hay un `k8s/*.yaml` por componente: ambos servicios se despliegan con el **chart genérico**
`infra/charts/generic-app` (compartido por todo el monorepo), parametrizado por
`infra/values-autoops-backend.yaml` y `infra/values-autoops-frontend.yaml`. `infra/scripts/deploy.sh` resuelve
los nombres por convención (`--project autoops`) y hace `helm upgrade --install` por componente; el namespace
(`infra/namespace-autoops.yaml`) y el RBAC (`infra/rbac-autoops.yaml`, guía 02) se aplican antes, también de
forma automática.

El backend corre con el ServiceAccount `autoops-mcp` (RBAC read-only de la guía 02; `serviceAccount.create:
false` en el values — su ciclo de vida es cluster-scoped, fuera del release de Helm) para las tools de
diagnóstico. Backend y frontend son **dos Deployments separados** (dos imágenes), ambos detrás del mismo
Ingress (guía 06): no hay un único "componente expuesto".

Equivalente conceptual de lo que el chart renderiza para el **backend** (valores reales y comentados en
`infra/values-autoops-backend.yaml`; release `autoops-backend` → recursos `autoops-backend-generic-app`):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: autoops-backend-generic-app
  namespace: autoops
spec:
  replicas: 1
  strategy:                                # RollingUpdate, maxUnavailable: 0 (servidor stateless)
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  template:
    spec:
      serviceAccountName: autoops-mcp        # RBAC read-only (guía 02)
      containers:
        - name: app
          image: registry.local/autoops-backend:<tag>
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          envFrom: [{ configMapRef: { name: autoops-backend-generic-app } }]   # SPRING_PROFILES_ACTIVE=k8s, etc.
          env:
            - name: GEMINI_API_KEY
              valueFrom: { secretKeyRef: { name: autoops-llm-secret, key: GEMINI_API_KEY } }
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: "2", memory: 1Gi }
          readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 } }
          livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 } }
---
apiVersion: v1
kind: Service
metadata: { name: autoops-backend-generic-app, namespace: autoops }
spec:
  selector: { app.kubernetes.io/instance: autoops-backend }   # ver nota de labels en §3
  ports: [{ port: 8080, targetPort: 8080 }]
```

El **frontend** (`autoops-frontend-generic-app`) sigue el mismo patrón con `infra/values-autoops-frontend.yaml`:
imagen `registry.local/autoops-frontend`, puerto `80` (nginx), sin ConfigMap ni Secret (config embebida en el
build Angular).

## 2. Despliegue y verificación

```bash
./infra/scripts/deploy.sh --project autoops --component all --tag 0.4.0
kubectl get pods -n autoops          # autoops-backend-generic-app-... y autoops-frontend-generic-app-... Running
```

Prueba de humo sin Ingress (port-forward directo):

```bash
kubectl port-forward svc/autoops-backend-generic-app 8080:8080 -n autoops
curl http://localhost:8080/actuator/health      # UP
# El endpoint MCP (Streamable HTTP) responde en /mcp (lo consume el cliente MCP, no el navegador)
```

## 3. Decisiones de diseño

- **Probes de Actuator separadas**: readiness cae si Weaviate o el proveedor LLM no responden (deja de recibir
  tráfico sin reiniciarse); liveness solo reinicia ante cuelgues reales de la JVM. Requiere
  `management.endpoint.health.probes.enabled=true` (por defecto en Kubernetes).
- **CPU solo limitada arriba** (`limits`, holgado a 2 cores; `requests` modesto): evita throttling de la JVM en
  el nodo local; el límite de memoria sí protege el nodo único.
- **Una réplica por componente**: el backend es prácticamente stateless (el estado vive en Weaviate). El
  transporte MCP (**Streamable HTTP, con conexión persistente**) reconecta solo; si en el futuro se escalara a
  N réplicas habría que fijar afinidad de sesión en el Ingress — en local, una réplica es suficiente.
- **ServiceAccount de solo lectura**: el contenedor del backend monta automáticamente el token del SA; el
  cliente fabric8 lo usa *in-cluster* sin configuración extra. El frontend no necesita SA propio.
- **Labels de Helm, no `app=`**: el chart genérico fija `app.kubernetes.io/name` al nombre del *chart*
  (`generic-app`) para **todos** los servicios que lo usan — no distingue backend de frontend. Lo que sí
  distingue cada release es `app.kubernetes.io/instance` (`autoops-backend` / `autoops-frontend`); los
  selectores de las tools K8s (`estado_pods`) deben usar ese label, no `app=` ni `app.kubernetes.io/name=`.

Continuar con [06-ingress-acceso.md](06-ingress-acceso.md).
