# 07 — Operación y troubleshooting

## 1. Comandos del día a día

Los Deployments/Services reales son `autoops-backend-generic-app` y `autoops-frontend-generic-app` (nombre =
release Helm `autoops-<componente>` + chart `generic-app`, guía 05); no existe un `deployment/autoops-mcp`.

```bash
# Estado general
kubectl get pods,svc,ingress -n autoops

# Logs en vivo (backend = el servidor MCP/RAG; frontend = nginx con la SPA)
kubectl logs -f deployment/autoops-backend-generic-app -n autoops

# Logs del arranque anterior (si el pod reinició)
kubectl logs deployment/autoops-backend-generic-app -n autoops --previous

# Describir un pod con problemas (eventos al final). Selector por instance, NO por
# app= ni app.kubernetes.io/name= (el chart genérico fija ese label a "generic-app"
# para todos los servicios — ver nota de la guía 02).
kubectl describe pod -l app.kubernetes.io/instance=autoops-backend -n autoops

# Reinicio controlado
kubectl rollout restart deployment/autoops-backend-generic-app -n autoops
```

Con `k9s` instalado, `k9s -n autoops` da todo lo anterior de forma interactiva.

## 2. Fallos típicos y diagnóstico

| Síntoma | Causa probable | Solución |
|---|---|---|
| Pod `ErrImageNeverPull`/`ImagePullBackOff` | Imagen no visible para el clúster (push a `registry.local` falló o tag no coincide) | `deploy.sh` sin `--skip-build`; comprobar `docker images`/`docker push` y el `--tag` usado |
| `autoops-backend-generic-app` `CrashLoopBackOff` al arrancar | No conecta a Weaviate | `kubectl logs --previous`. ¿`weaviate-0` Ready en el namespace **`infra`** (no `autoops`)? ¿host/puerto correctos? |
| Readiness nunca pasa | Falta `GEMINI_API_KEY` o Weaviate no responde | `kubectl describe pod` + logs; verificar secret `autoops-llm-secret` y `weaviate` Ready |
| El cliente MCP no lista tools | Endpoint `/mcp` no accesible o mal registrado | Probar `http://autoops.local/actuator/health`; revisar la URL `/mcp` en la config del cliente y las anotaciones de Ingress (buffering off) |
| La conexión MCP se corta a los segundos | Ingress bufferiza o timeout corto | Anotaciones `proxy-buffering: off` y `proxy-read-timeout` altos (guía 06 §2) |
| El cliente MCP deja de poder llamar tools tras ~5 min idle (`-32001 Request timed out`) | Body-timeout del cliente (Node/undici) sobre una conexión sin keep-alive | Asegurar `spring.ai.mcp.server.streamable-http.keep-alive-interval` configurado (p. ej. `20s`) en `application.yml`; si reaparece, reiniciar la conexión del cliente |
| `buscar_docs`/`/search` no devuelven nada o dan error de schema (`Cannot query field "titulo"...`) | Clase `AutoopsDocChunk` con esquema viejo (`ensureSchema()` es idempotente: si la clase ya existe, no la migra) | Re-ingestar con reset (§3): `bash infra/scripts/ingest-autoops.sh --reset` |
| `diagnosticar_pod` responde `403 Forbidden` | RBAC no aplicado o SA no asignado | `kubectl auth can-i list pods --as=system:serviceaccount:autoops:autoops-mcp -A` debe ser `yes`; ¿`serviceAccountName` en el Deployment? |
| Respuestas del LLM interno con `429` | Límite de la capa gratuita de Gemini | Esperar la ventana de cuota; `gemini-2.5-flash-lite` tiene la cuota más alta; reducir tamaño de lote en la ingesta |
| Despliego imagen nueva y no cambia nada | Etiqueta reutilizada con `IfNotPresent` | `--tag` único en `deploy.sh` (guía 04 §3); nunca reutilizar tag |

## 3. Ingesta del corpus (poblar el RAG)

Tras desplegar (o tras un cambio de esquema/modelo de embeddings), hay que indexar la documentación interna en
Weaviate. El script `infra/scripts/ingest-autoops.sh` automatiza el port-forward + las llamadas a los endpoints
internos (`IngestionController`, NO expuestos por el Ingress):

```bash
bash infra/scripts/ingest-autoops.sh            # ingesta incremental: corpus local + repo Git (no borra nada)
bash infra/scripts/ingest-autoops.sh --reset    # vacía Weaviate y reindexa todo desde cero
```

Equivalente manual (lo que hace el script por dentro): el repo Git se pasa por parámetro, no por config, así
que se puede repetir con otra `url` para ir sumando más repos a la wiki:

```bash
kubectl port-forward svc/autoops-backend-generic-app 8080:8080 -n autoops
# En otra terminal:
curl -X POST "http://localhost:8080/admin/vectorstore/drop"          # solo si quieres partir de cero
curl -X POST "http://localhost:8080/admin/ingest"                    # corpus local (+ Wiki.js si está activo)
curl -X POST "http://localhost:8080/admin/ingest/git?url=https://github.com/mikel0ne/langchain.git"
# → { "url": "...", "branch": "main", "chunks": 142 }   (la rama se detecta sola si no se indica)
```

Verificar que `AutoopsDocChunk` tiene objetos (Weaviate vive en el namespace **`infra`**, compartido — no
`autoops`):

```bash
kubectl port-forward svc/weaviate 8088:8080 -n infra
curl "http://localhost:8088/v1/objects?class=AutoopsDocChunk&limit=1"
```

> Re-ingestar con `--reset` es **obligatorio** si cambia el **modelo de embeddings** (cambia la dimensión del
> vector), el **esquema** de `AutoopsDocChunk` (`ensureSchema()` es idempotente, no migra solo) o el corpus.

## 4. Actualizar la API key del LLM

El valor vive en `infra/.env.autoops.local` (gitignored) o `infra/.env.local` (compartida); tras editarlo,
`deploy.sh` resincroniza el Secret automáticamente (`--skip-build` si no hace falta rebuild de imagen):

```bash
./infra/scripts/deploy.sh --project autoops --component backend --skip-build
kubectl rollout restart deployment/autoops-backend-generic-app -n autoops
```

Equivalente manual (lo que hace `sync_secrets` por dentro):

```bash
kubectl create secret generic autoops-llm-secret -n autoops \
  --from-literal=GEMINI_API_KEY="nueva-clave" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deployment/autoops-backend-generic-app -n autoops
```

## 5. Consumo de recursos

```powershell
# Requiere metrics-server (Minikube: addon; Docker Desktop no lo trae)
kubectl top pods -n autoops
```

Presupuesto orientativo en reposo: ~1,2 GB RAM (JVM ≈ 600 MB, Weaviate ≈ 300 MB, ingress ≈ 100 MB). El reranker
opcional añade ~700 MB-1 GB.

## 6. Limpieza

**Weaviate vive en el namespace `infra`, COMPARTIDO con otros proyectos del portfolio** (criterio Postgres,
guía 03 §1): nunca escalarlo a 0 ni borrarlo desde la limpieza de AutoOps, rompería a los demás.

```bash
# Parar AutoOps conservando datos propios (escala a 0; Weaviate, en infra, no se toca)
kubectl scale deployment --all --replicas=0 -n autoops

# Rearrancar
kubectl scale deployment --all --replicas=1 -n autoops

# Borrado completo de AutoOps (NO afecta a Weaviate/infra: su índice y PVC viven en otro namespace)
kubectl delete namespace autoops
kubectl delete clusterrole autoops-mcp-readonly
kubectl delete clusterrolebinding autoops-mcp-readonly
```

Para borrar solo los datos propios de AutoOps dentro del Weaviate compartido (sin tocar el resto del clúster),
usar la ingesta con reset (§3): `bash infra/scripts/ingest-autoops.sh --reset` borra la clase
`AutoopsDocChunk` y la reindexa desde cero.

## 7. Checklist de despliegue desde cero

1. ☐ Clúster activo (`kubectl get nodes`).
2. ☐ Namespace + secret + RBAC read-only (guía 02); `auth can-i get secrets` → **no**.
3. ☐ Weaviate Ready en `infra` (`/v1/.well-known/ready`) (guía 03).
4. ☐ Imágenes `registry.local/autoops-backend` y `registry.local/autoops-frontend` construidas y visibles (guía 04).
5. ☐ `autoops-backend-generic-app` y `autoops-frontend-generic-app` `READY 1/1` (guía 05).
6. ☐ Ingress instalado (con `/mcp`) + hosts editado (guía 06).
7. ☐ Corpus ingestado (`AutoopsDocChunk` con objetos) (§3).
8. ☐ Cliente MCP (Claude Code) **lista las tools** vía `http://autoops.local/mcp`.
9. ☐ Pod roto a propósito → el cliente diagnostica citando el evento real (`ErrImagePull`).
