# 04 — Backend (Spring Boot + LangGraph4j)

**Prerrequisitos:** guía 03 (infra sana, BD `l1_copilot` con esquema y seed cargados),
backend con perfil `prod` (Fases 0-4 ✅).
**Resultado:** backend desplegado en `l1-copilot` con el chart `generic-app`, conectado
al Postgres compartido y a Gemini, expuesto internamente como
`l1-copilot-backend-generic-app:8080` y públicamente vía el Ingress de la guía 06
en `http://l1-copilot.local/api/...` (incluyendo el actuator).

## 1. Imagen

[`docker/backend.Dockerfile`](../../docker/backend.Dockerfile) — multi-stage (Maven con
caché de dependencias → JRE 17 alpine, usuario no-root).

```bash
docker build -t registry.local/l1-copilot-backend:1.0.0 -f docker/backend.Dockerfile l1-copilot/backend
docker push registry.local/l1-copilot-backend:1.0.0
```

> **El camino real es `deploy.sh`** (build + push + secrets + helm en uno):
> `infra/scripts/deploy.sh --project l1-copilot --component backend --tag <tag>`. Usa el
> contexto `l1-copilot/backend` declarado en `infra/projects/l1-copilot.env`. Los comandos
> manuales de abajo describen lo que el script hace por dentro. **Pasar SIEMPRE un `--tag`
> único** (mismo tag + `IfNotPresent` = el pod no se actualiza).

> **Por qué push al registry y no `IfNotPresent` directo:** el modelo es multi-servicio
> y debe funcionar igual si mañana hay dos nodos. El motor moby permitiría saltarse el
> registry en un nodo único, pero deja la imagen invisible para cualquier otro contexto.
> El NodePort `30500` está disponible nada más correr `02-setup-registry.sh`.

## 2. Configuración por entorno

El contenedor es el mismo para cualquier entorno; todo lo variable entra por entorno
(declarado en [`infra/values-l1-copilot-backend.yaml`](../../infra/values-l1-copilot-backend.yaml)):

| Variable | Origen | Valor |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ConfigMap (del chart) | `prod` |
| `SPRING_DATASOURCE_URL` | ConfigMap | `jdbc:postgresql://postgres.infra.svc.cluster.local:5432/l1_copilot` |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | Secret `postgres-credentials` (ns `l1-copilot`) | `appuser` / `change-me` |
| `GEMINI_API_KEY` | Secret `gemini-api-key` | — |
| `langchain4j.gemini.model-name` | `application.yml` (no se sobrescribe en prod) | `gemini-3.1-flash-lite` |
| `JAVA_TOOL_OPTIONS` | ConfigMap | `-XX:MaxRAMPercentage=75` |

Los Secrets los sincroniza `deploy.sh` automáticamente desde `infra/.env.l1-copilot` +
`infra/.env.local` (git-ignored, compartida por todos los proyectos del portfolio),
según el mapeo `SECRETS_BACKEND` de `infra/projects/l1-copilot.env` (`gemini-api-key` ←
`GEMINI_API_KEY`, `postgres-credentials` ← `POSTGRES_USER`/`POSTGRES_PASSWORD`, ya
públicos en `infra/.env.l1-copilot`). Solo hay que rellenar el `.env.local` una vez
(key gratuita de Gemini en
[aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)). Ver guía 07.

Si se quieren crear a mano (lo que `deploy.sh` hace por dentro):

```bash
kubectl apply -f infra/namespace-l1-copilot.yaml      # crea el namespace l1-copilot

kubectl create secret generic gemini-api-key -n l1-copilot \
  --from-literal=GEMINI_API_KEY=<TU_API_KEY>

kubectl create secret generic postgres-credentials -n l1-copilot \
  --from-literal=POSTGRES_USER=appuser \
  --from-literal=POSTGRES_PASSWORD=change-me
```

> El password debe coincidir con el del Postgres compartido (lo fija
> `03-setup-postgres.sh`; valor por defecto `change-me`).

## 3. Desplegar

Forma real (recomendada):

```bash
infra/scripts/deploy.sh --project l1-copilot --component backend --tag <tag-único>
kubectl rollout status deploy/l1-copilot-backend -n l1-copilot --timeout=180s
```

Lo que hace por dentro con el chart `generic-app`:

```bash
helm upgrade --install l1-copilot-backend infra/charts/generic-app \
  -f infra/values-l1-copilot-backend.yaml --set image.tag=<tag-único>
```

Puntos clave del values
([`infra/values-l1-copilot-backend.yaml`](../../infra/values-l1-copilot-backend.yaml)):

- **`replicaCount: 1` + `strategy.type: Recreate`** — obligatorio mientras el estado
  conversacional sea `MemorySaver` en memoria (D-8).
- **Context-path `/api`**: `server.servlet.context-path: /api` en `application.yml`.
  Todos los controllers usan rutas relativas (`@RequestMapping("/copilot")`, `/cache`,
  `/tickets`, `/returns`); el contexto añade `/api` automáticamente. El actuator queda en
  `/api/actuator/*`. Ventaja: ni el Ingress ni el frontend necesitan reescribir paths.
- **Probes Actuator**: liveness `/api/actuator/health/liveness`, readiness
  `/api/actuator/health/readiness` (grupos habilitados en el perfil `prod`), con
  `initialDelaySeconds` generoso (arranque Spring + primera conexión a BD).
- **Recursos:** requests 512Mi/250m, limits 1Gi/1000m.
- **`pullPolicy: IfNotPresent`** — basta porque el tag es semver, no `latest`.
- **Service ClusterIP `l1-copilot-backend-generic-app:8080`** (el chart Helm
  `generic-app` nombra los Services como `<release>-generic-app`).
- **Ingress**: `ingress.enabled: false` en el values — el Ingress lo aplica el script
  genérico `infra/scripts/setup-app-ingress.sh --project l1-copilot` con `kubectl` (guía 06).
- **`secretEnv`**: declara las referencias a los Secrets `gemini-api-key` y
  `postgres-credentials`; **el chart NO crea esos Secrets** (los sincroniza `deploy.sh`).
- **Modelo Gemini**: `application.yml` fija `gemini-3.1-flash-lite` y `application-prod.yml`
  **no lo sobrescribe** (solo cambia `server.port`, logging, datasource y el turn-log); el
  modelo es el mismo en dev/local/prod.

## 4. Verificación

```bash
kubectl logs deploy/l1-copilot-backend -n l1-copilot --tail=20    # "Started Application in ..."

# Salud desde dentro del clúster (recuerda el /api del context-path)
kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- \
  curl -s http://l1-copilot-backend-generic-app:8080/api/actuator/health
# {"status":"UP", ...}  (incluye db: UP contra la BD l1_copilot del Postgres compartido)

# Turno de chat real (grafo + Gemini + Postgres)
kubectl run tmp --rm -it --image=curlimages/curl -n l1-copilot --restart=Never -- \
  curl -s -X POST http://l1-copilot-backend-generic-app:8080/api/copilot/chat \
       -H "Content-Type: application/json" -H "X-User-Id: u-001" \
       -d '{"conversationId":"smoke-1","message":"Hola"}'
```

✅ **Listo cuando:** health `UP` (con `db: UP`) y el turno de chat devuelve respuesta
del agente.

## Troubleshooting

| Síntoma | Causa | Arreglo |
|---|---|---|
| `ErrImagePull` / `ImagePullBackOff` | imagen no en el registry o tag distinto | `docker push localhost:30500/...` con tag exacto; `curl http://localhost:30500/v2/l1-copilot-backend/tags/list` |
| `CrashLoopBackOff`: "Failed to configure a DataSource" | Secrets/ConfigMap no llegan | `kubectl describe pod -n l1-copilot`: ¿existen los Secrets? `kubectl get configmap l1-copilot-backend -n l1-copilot -o yaml` |
| Readiness nunca pasa | Postgres caído, BD `l1_copilot` no creada, o credenciales mal | guía 03 §3; `kubectl logs`: ¿`database "l1_copilot" does not exist`? ejecutar `l1-copilot/scripts/setup-l1-copilot-db.sh` |
| Chat devuelve 503 | `GEMINI_API_KEY` vacía o cuota agotada | rotar key (guía 07) |
| `OOMKilled` | heap > limit | subir limit o bajar `-XX:MaxRAMPercentage` |

## Ciclo de actualización

```bash
# Un solo comando: build + push + helm con un tag nuevo (NO reutilizar tag con IfNotPresent)
infra/scripts/deploy.sh --project l1-copilot --component backend --tag "$(date +%s)"

helm history l1-copilot-backend -n l1-copilot
helm rollback l1-copilot-backend <rev> -n l1-copilot   # si hace falta
```

> `strategy: Recreate` implica ~30-60 s de corte y pérdida de las conversaciones en
> memoria — avisar si hay demo en curso.
