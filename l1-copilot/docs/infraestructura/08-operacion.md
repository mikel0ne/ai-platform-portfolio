# 08 — Operación del clúster

Día a día una vez desplegado: observar, actualizar (Helm y app), recuperar y limpiar.

## Observabilidad

```bash
# Estado general
kubectl get all -n l1-copilot
kubectl get all -n infra
kubectl get all -n registry
helm ls -A                                     # releases y su estado/revisión

# Logs del agente en vivo (los nodos del grafo logean ---ROUTER---, ---TRACKING---, etc.)
kubectl logs -f deploy/l1-copilot-backend -n l1-copilot

# Solo errores
kubectl logs deploy/l1-copilot-backend -n l1-copilot --tail=500 | grep ERROR

# Eventos ordenados (primera parada cuando algo no arranca)
kubectl get events -n l1-copilot --sort-by=.lastTimestamp
kubectl get events -n infra --sort-by=.lastTimestamp
```

Salud HTTP desde fuera: `http://l1-copilot.local/api/actuator/health` (vía Traefik,
guía 06; recuerda el `/api` del context-path).

## Actualizar componentes

| Qué cambió | Pasos |
|---|---|
| Código backend | `infra/scripts/deploy.sh --project l1-copilot --component backend --tag <nuevo>` (build + push + helm; Recreate: ~30-60 s de corte, las conversaciones en memoria se pierden) |
| Código frontend | `infra/scripts/deploy.sh --project l1-copilot --component frontend --tag <nuevo>` (rolling, sin corte) |
| Values del chart (recursos, probes, env…) | editar `infra/values-l1-copilot-*.yaml` → volver a ejecutar `deploy.sh` para ese componente |
| Esquema de la BD del proyecto | editar `l1-copilot/backend/src/main/resources/schema-postgresql.sql` → `bash l1-copilot/scripts/setup-l1-copilot-db.sh` (CREATE TABLE IF NOT EXISTS; para cambios destructivos: `DROP TABLE` o `DROP DATABASE l1_copilot` y re-ejecutar) |
| Seed de la BD del proyecto | editar `l1-copilot/backend/src/main/resources/data-postgresql.sql` → `bash l1-copilot/scripts/seed-l1-copilot-db.sh` |
| Versión del chart Postgres / Registry | re-ejecutar `infra/scripts/03-setup-postgres.sh` o `02-setup-registry.sh` con la imagen nueva (editar la variable `PG_VERSION` o el tag) |
| Secretos | guía 07 §4 (apply del secret + rollout restart) |

### Rollback con Helm

```bash
helm history l1-copilot-backend -n l1-copilot
helm rollback l1-copilot-backend <revision> -n l1-copilot
helm status l1-copilot-backend -n l1-copilot
```

## Backup y restauración de la BD del proyecto

```bash
# Dump
kubectl exec -n infra deploy/postgres -- pg_dump -U appuser l1_copilot > backup.sql

# Restore
cat backup.sql | kubectl exec -i -n infra deploy/postgres -- psql -U appuser -d l1_copilot
```

## Parar sin perder nada / arrancar de nuevo

Lo más simple con Rancher Desktop: **salir de la aplicación** (la VM se detiene y
libera toda la RAM; los PVCs persisten). Al arrancar Rancher Desktop, k3s levanta todo
solo. Para parar selectivamente dentro del clúster:

```bash
kubectl scale deploy/l1-copilot-backend deploy/l1-copilot-frontend -n l1-copilot --replicas=0
kubectl scale deploy/postgres -n infra --replicas=0
kubectl scale deploy/registry -n registry --replicas=0
# arrancar: mismos comandos con --replicas=1 (postgres primero)
```

## Limpieza

```bash
# Solo la aplicación
helm uninstall l1-copilot-backend l1-copilot-frontend -n l1-copilot
kubectl delete namespace l1-copilot

# Solo la BD del proyecto (deja appdb y el resto intactos)
kubectl exec -n infra deploy/postgres -- psql -U appuser -d postgres \
  -c "DROP DATABASE l1_copilot;"

# La infra compartida (¡afecta a TODOS los proyectos que la usen!)
kubectl delete namespace infra registry

# Imágenes locales
docker rmi localhost:30500/l1-copilot-backend:1.0.0 localhost:30500/l1-copilot-frontend:1.0.0

# Borrón y cuenta nueva del clúster entero (sin reinstalar Rancher Desktop)
# Rancher Desktop → Troubleshooting → Reset Kubernetes
```

## Runbook de incidencias frecuentes

| Alarma | Diagnóstico | Acción |
|---|---|---|
| Chat devuelve 503 | `kubectl logs deploy/l1-copilot-backend -n l1-copilot \| grep generativelanguage` → ¿429/403? | cuota AI Studio agotada o key inválida: rotar key (guía 07) |
| Chat "olvida" conversaciones | `kubectl get pods -n l1-copilot` → ¿RESTARTS > 0? | estado en memoria (D-8): reinicio = memoria limpia; investigar el motivo (OOM, probe) |
| Pod backend `OOMKilled` | `kubectl describe pod` → Last State: OOMKilled | subir `limits.memory` o bajar `-XX:MaxRAMPercentage` |
| Todo `Pending` | `kubectl describe pod` → "Insufficient memory/cpu" | subir RAM/CPU de la VM (en WSL: `%USERPROFILE%\.wslconfig` + `wsl --shutdown`) |
| Release de Helm en `failed` | `helm status <release> -n l1-copilot` | `helm rollback`; si el install inicial falló, `helm uninstall` + reinstalar |
| `kubectl` no conecta | contexto equivocado o Rancher Desktop parado | `kubectl config use-context rancher-desktop`; arrancar Rancher Desktop |

## Mejoras "v2" (apuntadas, no implementadas)

- Checkpointer persistente (Postgres) → `replicaCount > 1` y rolling updates sin
  pérdida de conversaciones.
- Migraciones con Flyway en vez del script `setup-l1-copilot-db.sh`.
- Prometheus + Grafana en `infra` (otro chart genérico o releases con sus propios
  charts; Actuator ya expone métricas).
- CI: build + push al registry de `infra` y despliegue con GitHub Actions.
