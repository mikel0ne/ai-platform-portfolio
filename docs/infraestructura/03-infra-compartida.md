# 03 — Infra compartida (registry + PostgreSQL) y BD del proyecto

**Prerrequisitos:** guía 02 (Rancher Desktop operativo).
**Resultado:** namespaces `registry` e `infra` con sus respectivos pods, y la BD
`l1_copilot` creada dentro del Postgres compartido con el esquema + seed del proyecto.

| Componente | Script | Namespace | Acceso |
|---|---|---|---|
| Registry de imágenes | [`infra/scripts/02-setup-registry.sh`](../../infra/scripts/02-setup-registry.sh) | `registry` | NodePort `localhost:30500` |
| PostgreSQL 16 (Alpine) | [`infra/scripts/03-setup-postgres.sh`](../../infra/scripts/03-setup-postgres.sh) | `infra` | en-cluster `postgres.infra.svc.cluster.local:5432` · NodePort `localhost:15432` |
| BD del proyecto | [`scripts/setup-l1-copilot-db.sh`](../../scripts/setup-l1-copilot-db.sh) | (dentro de Postgres) | BD `l1_copilot`, user `appuser` |

## 1. Registry de imágenes

```bash
bash infra/scripts/02-setup-registry.sh
```

Despliega `registry:2` en el namespace `registry` con NodePort `30500` y configura el
daemon Docker para tratar `localhost:30500` como registry inseguro (HTTP).

Verificación:

```bash
kubectl rollout status deploy/registry -n registry
curl -s http://localhost:30500/v2/_catalog        # {"repositories":[...]}
```

Uso (en builds posteriores):

```bash
docker build -t localhost:30500/l1-copilot-backend:1.0.0 -f docker/backend.Dockerfile .
docker push localhost:30500/l1-copilot-backend:1.0.0
```

Los pods referencian la imagen con el mismo nombre (`localhost:30500/...`): dentro del
nodo k3s, `localhost:30500` es el propio NodePort.

> **Opcional — URL "limpia"**: el script
> [`05-setup-registry-ingress.sh`](../../infra/scripts/05-setup-registry-ingress.sh)
> añade un entrypoint TCP en Traefik y expone el registry también en
> `localhost:15000` (y opcionalmente `registry.local:15000` tras ejecutar
> `add-registry-host.ps1` como Administrador).

## 2. PostgreSQL compartido

```bash
bash infra/scripts/03-setup-postgres.sh
```

Despliega `postgres:16-alpine` en el namespace `infra` con:

- Usuario admin: `appuser` · password: `change-me` · BD por defecto: `appdb`
  (credenciales en el Secret `postgres-credentials` del namespace `infra`).
- Persistencia: PVC de 1 Gi (`postgres-pvc`).
- Recursos contenidos: 100m/128Mi (requests), 500m/512Mi (limits).
- Service `postgres` (NodePort `15432`).

**DNS interno** para las aplicaciones: `postgres.infra.svc.cluster.local:5432`.

> **Opcional — acceso desde el host**:
> [`04-setup-postgres-ingress.sh`](../../infra/scripts/04-setup-postgres-ingress.sh)
> añade un entrypoint TCP en Traefik y expone Postgres en `localhost:5432`
> (más natural que el NodePort `15432`). Combinado con `add-postgres-host.ps1` se llega
> a `postgres.local:5432`.

### Verificación

```bash
kubectl rollout status deploy/postgres -n infra
kubectl exec -n infra deploy/postgres -- psql -U appuser -d appdb -c "SELECT version();"
```

## 3. BD del proyecto: `l1_copilot`

El Postgres compartido nace vacío (solo con `appdb`). Cada proyecto crea **su BD**
dentro del mismo motor con un script propio. Para el L1 copilot:

```bash
bash scripts/setup-l1-copilot-db.sh
```

El script (idempotente):

1. Crea la BD `l1_copilot` si no existe.
2. Aplica `backend/src/main/resources/schema-postgresql.sql` (las tablas usan
   `CREATE TABLE IF NOT EXISTS`).
3. Aplica `backend/src/main/resources/data-postgresql.sql` (seed) **solo si la BD se
   acaba de crear**. Para reaplicar el seed manualmente: `bash scripts/setup-l1-copilot-db.sh --reseed`
   (el SQL hace `TRUNCATE` + `INSERT`, idempotente).

Verificación:

```bash
kubectl exec -n infra deploy/postgres -- psql -U appuser -d l1_copilot \
  -c "SELECT id, description, status FROM orders WHERE user_id='u-001';"
# id  | description                   | status
# ----+-------------------------------+-------------
# 123 | Portátil Lenovo ThinkPad X1   | IN_DELIVERY
# 456 | Ratón inalámbrico Logitech    | PAID
# 789 | Monitor 27" Dell              | DELIVERED
```

✅ **Listo cuando:** registry responde el catálogo, Postgres devuelve la versión y la
BD `l1_copilot` muestra los pedidos del seed (#123 IN_DELIVERY, #456 PAID, #789 DELIVERED).

## Mantenimiento

```bash
# Reaplicar el seed (idempotente: TRUNCATE + INSERT en el .sql)
bash scripts/setup-l1-copilot-db.sh --reseed

# Backup
kubectl exec -n infra deploy/postgres -- pg_dump -U appuser l1_copilot > backup.sql

# Restore
cat backup.sql | kubectl exec -i -n infra deploy/postgres -- psql -U appuser -d l1_copilot

# Borrar la BD del proyecto (deja appdb y el resto intactos)
kubectl exec -n infra deploy/postgres -- psql -U appuser -d postgres \
  -c "DROP DATABASE l1_copilot;"
```

## Troubleshooting

| Síntoma | Causa típica | Arreglo |
|---|---|---|
| `docker push localhost:30500/...` falla | NodePort no expuesto o daemon Docker sin registry inseguro | re-ejecutar `02-setup-registry.sh` (idempotente) |
| `setup-l1-copilot-db.sh` falla con "no such file" | no estás en la raíz del repo | `cd` al directorio que contiene `backend/`, `infra/`, `scripts/` |
| Backend: `password authentication failed` | el Secret `postgres-credentials` del namespace `l1-copilot` no coincide con `appuser/change-me` | regenerar el Secret (guía 07) |
| Seed cargado dos veces (duplicados) | re-ejecutaste con `--reseed` y el SQL no incluye `TRUNCATE` | `TRUNCATE` está en `data-postgresql.sql`; revisar |
| Pod postgres `CrashLoopBackOff` | PVC corrupto tras parada brusca | borrar PVC: `kubectl delete pvc postgres-pvc -n infra` (pierdes datos) y re-ejecutar `03-setup-postgres.sh` |
