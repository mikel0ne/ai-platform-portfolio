# 07 — Operación y troubleshooting

## 1. Comandos del día a día

```powershell
# Estado general
kubectl get pods,svc,ingress -n autoops

# Logs en vivo
kubectl logs -f deployment/autoops-mcp -n autoops

# Logs del arranque anterior (si el pod reinició)
kubectl logs deployment/autoops-mcp -n autoops --previous

# Describir un pod con problemas (eventos al final)
kubectl describe pod -l app=autoops-mcp -n autoops

# Reinicio controlado
kubectl rollout restart deployment/autoops-mcp -n autoops
```

Con `k9s` instalado, `k9s -n autoops` da todo lo anterior de forma interactiva.

## 2. Fallos típicos y diagnóstico

| Síntoma | Causa probable | Solución |
|---|---|---|
| Pod `ErrImageNeverPull`/`ImagePullBackOff` | Imagen no visible para el clúster | Docker Desktop: ¿etiqueta exacta del Deployment? Minikube: falta `minikube image load` |
| `autoops-mcp` `CrashLoopBackOff` al arrancar | No conecta a Weaviate | `kubectl logs --previous`. ¿`weaviate-0` Ready? ¿host/puerto correctos? |
| Readiness nunca pasa | Falta `GEMINI_API_KEY` o Weaviate no responde | `kubectl describe pod` + logs; verificar secret `autoops-llm-secret` y `weaviate` Ready |
| El cliente MCP no lista tools | Endpoint SSE no accesible o mal registrado | Probar `http://autoops.local/actuator/health`; revisar la URL `/sse` en la config del cliente y las anotaciones de Ingress (buffering off) |
| La conexión MCP se corta a los segundos | Ingress bufferiza o timeout corto | Anotaciones `proxy-buffering: off` y `proxy-read-timeout` altos (guía 06 §2) |
| `buscar_docs` no devuelve nada | Corpus sin ingestar o clase `DocChunk` vacía | Ejecutar la ingesta (§3); comprobar objetos en Weaviate |
| `diagnosticar_pod` responde `403 Forbidden` | RBAC no aplicado o SA no asignado | `kubectl auth can-i list pods --as=system:serviceaccount:autoops:autoops-mcp -A` debe ser `yes`; ¿`serviceAccountName` en el Deployment? |
| Respuestas del LLM interno con `429` | Límite de la capa gratuita de Gemini | Esperar la ventana de cuota; `gemini-2.5-flash-lite` tiene la cuota más alta; reducir tamaño de lote en la ingesta |
| Despliego imagen nueva y no cambia nada | Etiqueta reutilizada con `IfNotPresent` | Etiqueta nueva por build (`0.1.x`) + `kubectl set image` (guía 04 §4) |

## 3. Ingesta del corpus (poblar el RAG)

Tras desplegar, hay que indexar la documentación interna en Weaviate. Según cómo se haya implementado la ingesta
(comando de arranque o endpoint), por ejemplo vía endpoint interno con port-forward:

```powershell
kubectl port-forward svc/autoops-mcp 8080:8080 -n autoops
# En otra terminal — lanzar la ingesta del corpus montado en la imagen:
Invoke-RestMethod -Method Post http://localhost:8080/admin/ingest -Body '{"clear":true}' -ContentType 'application/json'
# → { "documentos": 6, "chunks": 142 }
```

Verificar que `DocChunk` tiene objetos:

```powershell
kubectl port-forward svc/weaviate 8088:8080 -n autoops
Invoke-RestMethod "http://localhost:8088/v1/objects?class=DocChunk&limit=1"
```

> Re-ingestar (con `clear:true`) es obligatorio si se cambia el **modelo de embeddings** (cambia la dimensión
> del vector) o el corpus.

## 4. Actualizar la API key del LLM

```powershell
kubectl create secret generic autoops-llm-secret -n autoops `
  --from-literal=GEMINI_API_KEY="nueva-clave" `
  --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deployment/autoops-mcp -n autoops
```

## 5. Consumo de recursos

```powershell
# Requiere metrics-server (Minikube: addon; Docker Desktop no lo trae)
kubectl top pods -n autoops
```

Presupuesto orientativo en reposo: ~1,2 GB RAM (JVM ≈ 600 MB, Weaviate ≈ 300 MB, ingress ≈ 100 MB). El reranker
opcional añade ~700 MB-1 GB.

## 6. Limpieza

```powershell
# Parar conservando datos (escala a 0)
kubectl scale deployment --all --replicas=0 -n autoops
kubectl scale statefulset weaviate --replicas=0 -n autoops

# Rearrancar
kubectl scale deployment --all --replicas=1 -n autoops
kubectl scale statefulset weaviate --replicas=1 -n autoops

# Borrado completo (¡incluye el índice: el PVC cae con el namespace!)
kubectl delete namespace autoops
kubectl delete clusterrole autoops-mcp-readonly
kubectl delete clusterrolebinding autoops-mcp-readonly
```

## 7. Checklist de despliegue desde cero

1. ☐ Clúster activo (`kubectl get nodes`).
2. ☐ Namespace + secret + RBAC read-only (guía 02); `auth can-i get secrets` → **no**.
3. ☐ Weaviate Ready (`/v1/.well-known/ready`) (guía 03).
4. ☐ Imagen `autoops/mcp` construida y visible (guía 04).
5. ☐ `autoops-mcp` `READY 1/1` (guía 05).
6. ☐ Ingress instalado (con SSE) + hosts editado (guía 06).
7. ☐ Corpus ingestado (`DocChunk` con objetos) (§3).
8. ☐ Cliente MCP (Claude Code) **lista las tools** vía `http://autoops.local/sse`.
9. ☐ Pod roto a propósito → el cliente diagnostica citando el evento real (`ErrImagePull`).
