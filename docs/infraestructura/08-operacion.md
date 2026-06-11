# 08 — Operación del clúster

Día a día una vez desplegado: observar, actualizar, recuperar y limpiar.

## Observabilidad

```powershell
# Estado de todo el namespace de un vistazo
kubectl get all -n l1-copilot

# Logs del agente en vivo (los nodos del grafo logean ---ROUTER---, ---TRACKING---, etc.)
kubectl logs -f deploy/backend -n l1-copilot

# Solo errores
kubectl logs deploy/backend -n l1-copilot --tail=500 | Select-String "ERROR"

# Eventos del namespace ordenados (la primera parada cuando algo no arranca)
kubectl get events -n l1-copilot --sort-by=.lastTimestamp

# Consumo real frente a limits (requiere metrics-server; en Docker Desktop: instalarlo aparte)
kubectl top pods -n l1-copilot
```

Salud HTTP desde fuera: `http://copilot.localtest.me/actuator/health` (vía Ingress, guía 06).

## Actualizar componentes

| Qué cambió | Pasos |
|---|---|
| Código backend | `docker build` con **tag nuevo** → editar `image:` en `k8s/backend.yaml` → `kubectl apply` (strategy Recreate: ~30-60 s de corte, y las conversaciones en memoria se pierden — avisar en demo) |
| Código frontend | igual con `frontend.Dockerfile` y `k8s/frontend.yaml` (rolling, sin corte) |
| Config no sensible | editar ConfigMap → `kubectl apply` → `kubectl rollout restart deploy/backend` (los env de ConfigMap no se refrescan en caliente) |
| Secretos | guía 07 §4 (apply del secret + rollout restart) |
| Esquema BD | con PVC persistente el initdb NO se re-ejecuta: aplicar cambios con `psql` o recrear el PVC (guía 03 §2) |

## Backup y restauración de la BD de demo

```powershell
# Dump
kubectl exec postgres-0 -n l1-copilot -- pg_dump -U copilot copilot > backup.sql

# Restore
Get-Content backup.sql | kubectl exec -i postgres-0 -n l1-copilot -- psql -U copilot -d copilot
```

## Parar sin perder nada / arrancar de nuevo

```powershell
# Parar (libera RAM; el PVC conserva los datos)
kubectl scale deploy/backend deploy/frontend -n l1-copilot --replicas=0
kubectl scale statefulset/postgres -n l1-copilot --replicas=0

# Arrancar
kubectl scale statefulset/postgres -n l1-copilot --replicas=1
kubectl scale deploy/backend deploy/frontend -n l1-copilot --replicas=1
```

## Limpieza total

```powershell
kubectl delete namespace l1-copilot          # borra todo, incluido el PVC (datos fuera)
docker rmi l1-copilot-backend:1.0.0 l1-copilot-frontend:1.0.0
# y si se quiere desinstalar el ingress controller:
kubectl delete -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml
```

## Runbook de incidencias frecuentes

| Alarma | Diagnóstico | Acción |
|---|---|---|
| Chat devuelve 503 | `kubectl logs deploy/backend \| Select-String "generativelanguage"` → ¿429/403? | cuota AI Studio agotada o key inválida: rotar key (guía 07) o esperar ventana de cuota |
| Chat "olvida" conversaciones | `kubectl get pods -l app=backend` → ¿RESTARTS > 0? | el estado es en-memoria (D-6): reinicio = memoria limpia; investigar el motivo del reinicio (OOM, probe) |
| Pod backend `OOMKilled` | `kubectl describe pod` → Last State: OOMKilled | subir `limits.memory` o bajar `-XX:MaxRAMPercentage` en `backend-config` |
| Todo `Pending` | `kubectl describe pod` → "Insufficient memory/cpu" | subir recursos de Docker Desktop (Settings → Resources) |
| `kubectl` no conecta | contexto equivocado o K8s de Docker Desktop parado | `kubectl config use-context docker-desktop`; reiniciar Docker Desktop |

## Mejoras "v2" (apuntadas, no implementadas)

- Checkpointer persistente (Postgres) → permitir `replicas > 1` y rolling updates sin pérdida de conversaciones.
- Migraciones con Flyway en vez de initdb ConfigMap.
- Helm chart o Kustomize overlays (dev/prod) en lugar de YAML plano.
- Prometheus + Grafana (Actuator ya expone métricas con `micrometer-registry-prometheus`).
- CI: build + push de imágenes a un registry y despliegue con GitHub Actions.
