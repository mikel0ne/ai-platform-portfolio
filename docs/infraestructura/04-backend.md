# 04 — Backend (Spring Boot + LangGraph4j)

**Prerrequisitos:** guía 03 (Postgres sano), código del backend con perfil `prod` implementado (Fases 0-4 del plan de arquitectura).
**Resultado:** backend desplegado, conectado a Postgres y a Gemini, expuesto internamente como `backend:8080`.

## 1. Imagen Docker

[`docker/backend.Dockerfile`](../../docker/backend.Dockerfile) — multi-stage:

1. **Stage build** (`maven:3.9-eclipse-temurin-17`): `mvn package -DskipTests` con cacheo de dependencias (se copia antes el `pom.xml` y se hace `dependency:go-offline`).
2. **Stage runtime** (`eclipse-temurin:17-jre-alpine`): solo el JAR, usuario no-root, `SERVER_PORT 8080`.

Build (desde la raíz del repo):

```powershell
docker build -f docker/backend.Dockerfile -t l1-copilot-backend:1.0.0 .
```

> Con el Kubernetes de Docker Desktop la imagen ya es visible para el clúster. Con kind: `kind load docker-image l1-copilot-backend:1.0.0 --name l1-copilot`.

## 2. Configuración por entorno

El contenedor es **el mismo para cualquier entorno**; todo lo variable entra por entorno:

| Variable | Origen | Ejemplo |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ConfigMap `backend-config` | `prod` |
| `SPRING_DATASOURCE_URL` | ConfigMap `backend-config` | `jdbc:postgresql://postgres:5432/copilot` |
| `SPRING_DATASOURCE_USERNAME` | Secret `postgres-credentials` (key `POSTGRES_USER`) | `copilot` |
| `SPRING_DATASOURCE_PASSWORD` | Secret `postgres-credentials` (key `POSTGRES_PASSWORD`) | — |
| `GEMINI_API_KEY` | Secret `gemini-api-key` | — |
| `JAVA_TOOL_OPTIONS` | ConfigMap | `-XX:MaxRAMPercentage=75` |

Crear el Secret de Gemini (una vez; la key se obtiene gratis en [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)):

```powershell
kubectl create secret generic gemini-api-key -n l1-copilot --from-literal=GEMINI_API_KEY=<TU_API_KEY>
```

## 3. Desplegar

```powershell
kubectl apply -f k8s/backend-configmap.yaml
kubectl apply -f k8s/backend.yaml
kubectl rollout status deploy/backend -n l1-copilot --timeout=180s
```

Puntos del manifiesto ([`k8s/backend.yaml`](../../k8s/backend.yaml)):

- **`replicas: 1` y `strategy: Recreate`** — obligatorio mientras el estado conversacional sea `MemorySaver` en memoria (D-6): un rolling update con 2 pods simultáneos repartiría turnos de una conversación entre pods distintos.
- **Probes sobre Actuator** (ya incluido en el proyecto): liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` con `initialDelaySeconds` generoso (Spring Boot + primera conexión a BD tardan ~20-40 s). Requiere habilitar los grupos de salud en el perfil `prod`:
  ```yaml
  management:
    endpoint:
      health:
        probes:
          enabled: true
  ```
- **Recursos:** requests 512Mi/250m, limits 1Gi/1000m.
- **`imagePullPolicy: IfNotPresent`** — imprescindible con imágenes locales sin registry (con `Always` el kubelet intentaría bajarla de Docker Hub y fallaría con `ErrImagePull`).
- **Service ClusterIP `backend:8080`** — solo el Ingress y otros pods lo ven; no se expone directamente.

## 4. Verificación

```powershell
kubectl get pods -n l1-copilot -l app=backend
kubectl logs deploy/backend -n l1-copilot --tail=20    # "Started Application in ..."

# Salud desde dentro del clúster
kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- `
  curl -s http://backend:8080/actuator/health
# {"status":"UP", ...}

# Turno de chat real (atraviesa grafo + Gemini + Postgres)
kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- `
  curl -s -X POST http://backend:8080/api/copilot/chat `
       -H "Content-Type: application/json" -H "X-User-Id: u-001" `
       -d '{\"conversationId\":\"smoke-1\",\"message\":\"Hola\"}'
```

✅ **Listo cuando:** health `UP` (incluye `db: UP`) y el turno de chat devuelve una respuesta del agente.

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff` | falta la imagen local o `imagePullPolicy: Always` | reconstruir imagen; comprobar tag exacto; policy `IfNotPresent` |
| `CrashLoopBackOff`, log "Failed to configure a DataSource" | variables de datasource no llegan | `kubectl describe pod`: revisar envFrom/secretKeyRef; ¿existe el Secret? |
| Readiness nunca pasa | BD caída o `initialDelaySeconds` corto | `kubectl get pods -l app=postgres`; subir delay |
| Respuestas 503 del chat | `GEMINI_API_KEY` vacía o cuota agotada | log del backend: error 4xx de generativelanguage; regenerar key |
| `OOMKilled` | heap > limit | subir limit a 1.5Gi o bajar `-XX:MaxRAMPercentage` |

## Ciclo de actualización del backend

```powershell
docker build -f docker/backend.Dockerfile -t l1-copilot-backend:1.0.1 .   # nueva versión
# editar k8s/backend.yaml: image: l1-copilot-backend:1.0.1
kubectl apply -f k8s/backend.yaml
```

> Versionar el tag (1.0.0 → 1.0.1) en vez de reutilizar `latest`: con `IfNotPresent`, un tag reutilizado **no** se recarga y se queda la imagen vieja. Es el error más común de este flujo.
