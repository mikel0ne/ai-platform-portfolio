# 03 — PostgreSQL

**Prerrequisitos:** guía 02 completada (clúster + namespace).
**Resultado:** PostgreSQL 16 corriendo con el esquema y el seed del copilot, con datos persistentes entre reinicios del pod.

## Piezas

| Manifiesto | Qué crea |
|---|---|
| Secret `postgres-credentials` (imperativo) | usuario, password y nombre de BD |
| `k8s/postgres-init-configmap.yaml` | `schema.sql` + `data.sql` montados en `/docker-entrypoint-initdb.d` |
| `k8s/postgres.yaml` | Service headless `postgres` + StatefulSet con PVC de 1 Gi |

## 1. Crear el Secret de credenciales

**Nunca** se commitea con valores reales; se crea una vez:

```powershell
kubectl create secret generic postgres-credentials -n l1-copilot `
  --from-literal=POSTGRES_USER=copilot `
  --from-literal=POSTGRES_PASSWORD=$(-join ((48..57)+(97..122) | Get-Random -Count 24 | ForEach-Object {[char]$_})) `
  --from-literal=POSTGRES_DB=copilot
```

(El password aleatorio solo lo necesitan los pods; si quieres conectarte tú, recupéralo: ver §4.)

La plantilla [`k8s/postgres-credentials.example.yaml`](../../k8s/postgres-credentials.example.yaml) documenta la forma del Secret para quien prefiera el camino declarativo.

## 2. Esquema y seed (initdb)

La imagen oficial `postgres` ejecuta en el **primer arranque** (BD vacía) todos los `.sql` de `/docker-entrypoint-initdb.d`, en orden alfabético. El ConfigMap [`k8s/postgres-init-configmap.yaml`](../../k8s/postgres-init-configmap.yaml) contiene:

- `01-schema.sql` — tablas `users`, `orders`, `tickets` (el mismo esquema del doc de arquitectura "03 — Modelo de datos").
- `02-data.sql` — seed de demo con fechas relativas a `CURRENT_DATE`.

```powershell
kubectl apply -f k8s/postgres-init-configmap.yaml
```

> ⚠️ Los scripts de init **solo corren con el volumen vacío**. Si cambias el esquema después, o borras el PVC (`kubectl delete pvc data-postgres-0 -n l1-copilot` con el StatefulSet escalado a 0) o aplicas el cambio a mano con `psql` (§4).

## 3. Desplegar el StatefulSet

```powershell
kubectl apply -f k8s/postgres.yaml
kubectl rollout status statefulset/postgres -n l1-copilot --timeout=180s
```

Puntos del manifiesto que merecen lectura (ver [`k8s/postgres.yaml`](../../k8s/postgres.yaml)):

- **Service headless** (`clusterIP: None`): da el DNS estable `postgres.l1-copilot.svc.cluster.local` (los pods del namespace llegan con `postgres:5432`).
- **`volumeClaimTemplates`**: PVC `data-postgres-0` de 1 Gi con la StorageClass por defecto de Docker Desktop (`hostpath`).
- **`PGDATA=/var/lib/postgresql/data/pgdata`**: subdirectorio para que el volumen montado no choque con `lost+found`.
- **Probes** con `pg_isready`: readiness cada 5 s, liveness más laxa.
- **Recursos**: requests 128Mi/100m, limits 512Mi/500m — suficiente para demo.

## 4. Verificación

```powershell
# Pod sano
kubectl get pods -n l1-copilot -l app=postgres
# postgres-0   1/1   Running

# Recuperar el password
$pw = kubectl get secret postgres-credentials -n l1-copilot -o jsonpath="{.data.POSTGRES_PASSWORD}"
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($pw))

# Seed cargado (3 pedidos de u-001)
kubectl exec -it postgres-0 -n l1-copilot -- psql -U copilot -d copilot `
  -c "SELECT id, description, status FROM orders WHERE user_id='u-001';"
```

✅ **Listo cuando:** la query devuelve los pedidos #123 (IN_DELIVERY), #456 (PAID) y #789 (DELIVERED).

## Troubleshooting

| Síntoma | Causa típica | Arreglo |
|---|---|---|
| `CreateContainerConfigError` | Secret `postgres-credentials` no existe o le falta una key | §1; `kubectl describe pod postgres-0` lo dice |
| `CrashLoopBackOff` con "initdb: directory not empty" | PVC con restos de un intento anterior | borrar PVC con StatefulSet a 0 réplicas y volver a aplicar |
| El seed no está | el volumen ya existía cuando se añadió el ConfigMap | recrear PVC o aplicar los SQL con `psql -f` |
| Backend: `Connection refused` a `postgres:5432` | readiness aún no OK o Service mal etiquetado | `kubectl get endpoints postgres -n l1-copilot` debe listar la IP del pod |
