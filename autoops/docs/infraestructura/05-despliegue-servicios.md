# 05 — Despliegue del servidor

Despliegue de `autoops-mcp`. Requiere completadas las guías 02 (secret/RBAC), 03 (Weaviate) y 04 (imagen
construida).

## 1. Deployment y Service (`k8s/20-autoops-mcp.yaml`)

La app corre con el ServiceAccount `autoops-mcp` (RBAC read-only de la guía 02) para las tools de diagnóstico.
Es el **único** componente expuesto por Ingress.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: autoops-mcp
  namespace: autoops
  labels:
    app: autoops-mcp
    app.kubernetes.io/part-of: autoops
spec:
  replicas: 1
  selector:
    matchLabels:
      app: autoops-mcp
  template:
    metadata:
      labels:
        app: autoops-mcp
        app.kubernetes.io/part-of: autoops
    spec:
      serviceAccountName: autoops-mcp          # RBAC read-only (guía 02)
      containers:
        - name: mcp
          image: autoops/mcp:0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: k8s }   # transporte MCP por HTTP/SSE
            # Proveedor LLM/embeddings abstraído
            - { name: AUTOOPS_LLM_PROVIDER, value: gemini }
            - name: GEMINI_API_KEY
              valueFrom:
                secretKeyRef: { name: autoops-llm-secret, key: GEMINI_API_KEY }
            # Weaviate
            - { name: AUTOOPS_WEAVIATE_HOST, value: weaviate.autoops.svc.cluster.local }
            - { name: AUTOOPS_WEAVIATE_PORT, value: "8080" }
            - { name: AUTOOPS_WEAVIATE_GRPC_PORT, value: "50051" }
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { memory: 1Gi }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 40
            periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: autoops-mcp
  namespace: autoops
spec:
  selector:
    app: autoops-mcp
  ports:
    - port: 8080
      targetPort: 8080
```

## 2. Despliegue y verificación

```powershell
kubectl apply -f k8s/20-autoops-mcp.yaml
kubectl rollout status deployment/autoops-mcp -n autoops
kubectl get pods -n autoops          # autoops-mcp y weaviate Running, READY 1/1
```

Prueba de humo sin Ingress (port-forward directo):

```powershell
kubectl port-forward svc/autoops-mcp 8080:8080 -n autoops
# En otra terminal:
Invoke-WebRequest http://localhost:8080/actuator/health      # UP
# El endpoint MCP por SSE responde en /sse (lo consume el cliente MCP, no el navegador)
```

## 3. Decisiones de diseño

- **Probes de Actuator separadas**: readiness cae si Weaviate o el proveedor LLM no responden (deja de recibir
  tráfico sin reiniciarse); liveness solo reinicia ante cuelgues reales de la JVM. Requiere
  `management.endpoint.health.probes.enabled=true` (por defecto en Kubernetes).
- **Sin límite de CPU** (solo `requests`): evita throttling de la JVM en local; el límite de memoria sí protege
  el nodo único.
- **Una réplica**: el servidor es prácticamente stateless (el estado vive en Weaviate). El transporte **SSE es
  con conexión persistente**, así que si en el futuro se escalara a N réplicas habría que fijar afinidad de
  sesión en el Ingress; en local, una réplica es suficiente.
- **ServiceAccount de solo lectura**: el contenedor monta automáticamente el token del SA; el cliente fabric8 lo
  usa *in-cluster* sin configuración extra.

Continuar con [06-ingress-acceso.md](06-ingress-acceso.md).
