# Infraestructura Local — Documentación

> Documento vivo. Se actualiza conforme se añaden componentes.

---

## Stack objetivo

| Capa | Componente | Estado |
|------|-----------|--------|
| Orquestación | Rancher Desktop (k3s) | ✅ Instalado |
| Ingress | Traefik (incluido en k3s) | ✅ Instalado |
| Package manager | Helm (incluido en Rancher Desktop) | ✅ Instalado |
| Registry | Registry local (puerto 30500) | ✅ Instalado |
| Base de datos | PostgreSQL 16 Alpine (namespace `infra`) | ✅ Instalado |
| Backend | Por definir | 🔲 No iniciado |
| Frontend | Por definir | 🔲 No iniciado |

---

## Componentes instalados

### 1. Rancher Desktop

- **Versión**: última estable
- **Kubernetes**: k3s
- **Runtime de contenedores**: dockerd (compatibilidad Docker CLI)
- **Ingress**: Traefik (incluido)
- **Script**: `scripts/01-install-rancher-desktop.sh`
- **Estado**: ✅ Instalado (k3s v1.35.5, CE: moby)

### 3. PostgreSQL 16 (Alpine)

- **Imagen**: `postgres:16-alpine` (~80 MB, la más ligera para POC con SQL completo)
- **Namespace**: `infra`
- **Versión instalada**: PostgreSQL 16.14 on x86_64-pc-linux-musl (Alpine)
- **Persistencia**: PersistentVolumeClaim `postgres-pvc` de 1 Gi
- **Recursos**: 100 m CPU / 128 Mi RAM (requests) · 500 m CPU / 512 Mi RAM (limits)
- **Script**: `scripts/03-setup-postgres.sh`
- **Estado**: ✅ Instalado y verificado (2026-06-12)

#### Credenciales

| Parámetro | Valor |
|-----------|-------|
| Base de datos | `appdb` |
| Usuario | `appuser` |
| Contraseña | `change-me` |
| Puerto interno | `5432` |
| Puerto externo (NodePort) | `15432` |

> ⚠️ Las credenciales se almacenan en el Secret `postgres-credentials` del namespace `infra`.  
> En producción rotar la contraseña y usar un gestor de secretos (Vault, Sealed Secrets, etc.).

#### Cadenas de conexión

| Contexto | Cuándo usarla |
|----------|---------------|
| **Interna (en-cluster)** | App desplegada dentro de Kubernetes (Spring Boot en un pod, otro microservicio, etc.) |
| **Externa (local/host)** | Desarrollo local, DBeaver, pruebas desde tu máquina o WSL |

**🔵 Interna — para apps dentro de Kubernetes**

```
# JDBC (application.properties / application.yml)
spring.datasource.url=jdbc:postgresql://postgres.infra.svc.cluster.local:5432/appdb
spring.datasource.username=appuser
spring.datasource.password=change-me

# psql desde dentro de un pod
psql -h postgres.infra.svc.cluster.local -p 5432 -U appuser -d appdb
```

> El hostname `postgres.infra.svc.cluster.local` solo resuelve dentro del clúster.  
> Formato: `<service>.<namespace>.svc.cluster.local`

---

**🟢 Externa — para desarrollo local (host / WSL)**

Rancher Desktop solo hace bridge automático a `localhost` de Windows para el **rango NodePort (30000-32767)**. No es posible usar puertos arbitrarios (ej. 5432, 54321) sin un proceso adicional.

La URL externa definitiva es:

```
postgres.local:15432
```

`postgres.local` resuelve a `127.0.0.1` vía fichero hosts de Windows (ya configurado).  
`15432` es el NodePort de k3s (rango ampliado a 10000-19999 en `/etc/rancher/k3s/config.yaml`).  
No requiere ningún proceso adicional.

```properties
# application.properties (perfil local)
spring.datasource.url=jdbc:postgresql://postgres.local:15432/appdb
spring.datasource.username=appuser
spring.datasource.password=change-me
spring.datasource.driver-class-name=org.postgresql.Driver

# psql desde el host o WSL
psql -h postgres.local -p 15432 -U appuser -d appdb
```

> Conexión validada: `TcpTestSucceeded: True` a `postgres.local:15432` desde Windows (2026-06-12).  
> Si hay que rehacerlo: ejecutar `scripts/add-postgres-host.ps1` como Administrador.

> ⚠️ Tras reiniciar Rancher Desktop, el kubeconfig de Ubuntu WSL se desincroniza.  
> Refrescarlo con: `cp /mnt/c/Users/<usuario>/.kube/config ~/.kube/config`

> El NodePort `30532` está expuesto por k3s en `localhost` de la máquina Windows / WSL.

#### Prueba de conexión realizada

```sql
-- Ejecutado el 2026-06-12 con kubectl exec en el pod postgres-*
\conninfo
-- You are connected to database "appdb" as user "appuser" via socket...

SELECT version();
-- PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit

CREATE TABLE healthcheck (id SERIAL PRIMARY KEY, ts TIMESTAMPTZ DEFAULT now(), note TEXT);
INSERT INTO healthcheck(note) VALUES ('setup ok');
SELECT * FROM healthcheck;
-- 1 | 2026-06-12 17:55:52.799964+00 | setup ok
```

#### Comandos útiles

```bash
# Ver el pod
kubectl get pods -n infra

# Ver logs
kubectl logs -n infra deployment/postgres

# Abrir consola psql dentro del pod
kubectl exec -it -n infra deployment/postgres -- psql -U appuser -d appdb

# Ver credenciales del Secret
kubectl get secret postgres-credentials -n infra -o jsonpath='{.data}' | base64 -d
```

---

### 4. Registry local

- **Imagen**: `registry:2`
- **Puerto**: `5000`
- **Almacenamiento**: volumen persistente local
- **Script**: `scripts/02-setup-registry.sh`
- **Estado**: ⏳ Pendiente (requiere Rancher Desktop instalado)

---

## Estructura del repositorio

```
infra/
├── INFRA.md                            # Este documento
└── scripts/
    ├── 01-install-rancher-desktop.sh       # Rancher Desktop (desde WSL2)
    ├── 02-setup-registry.sh               # Registry local en k3s
    ├── 03-setup-postgres.sh               # PostgreSQL 16 Alpine en namespace infra
    ├── 04-setup-postgres-ingress.sh       # Traefik TCP -> postgres.local:5432
    └── add-postgres-host.ps1              # (Admin) Añade postgres.local al hosts de Windows
```

---

## Requisitos previos

- Windows 10/11 64-bit con WSL2 (Ubuntu)
- Al menos 8 GB de RAM recomendados (mínimo 4 GB)
- winget disponible

---

## Notas

- El registry local solo consume recursos durante push/pull de imágenes.
- Traefik gestiona el ingress sin configuración adicional.
- Para acceder al registry desde dentro del clúster k3s se usa `localhost:5000`.
