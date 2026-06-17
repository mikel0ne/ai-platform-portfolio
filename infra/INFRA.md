# Infraestructura Local — Documentación

> Documento vivo. Se actualiza conforme se añaden componentes.

---

## Stack objetivo

| Capa | Componente | Estado |
|------|-----------|--------|
| Orquestación | Rancher Desktop (k3s) | ✅ Instalado |
| Ingress | Traefik (incluido en k3s) | ✅ Instalado |
| Package manager | Helm (incluido en Rancher Desktop) | ✅ Instalado |
| Registry | `registry.local` vía Traefik (80/443) | ✅ Instalado |
| Base de datos | PostgreSQL 16 Alpine (namespace `infra`) | ✅ Instalado |
| Backend | Spring Boot + LangGraph4j (namespace `l1-copilot`) | ✅ Desplegado |
| Frontend | Angular 17 + nginx (namespace `l1-copilot`) | ✅ Desplegado |

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
- **Namespace**: `registry`
- **Hostname canónico**: `registry.local` (sin puerto — vía Traefik)
- **Puertos expuestos**:
  - `http://registry.local` (Traefik entrypoint `web`, puerto 80)
  - `https://registry.local` (Traefik entrypoint `websecure`, puerto 443, cert por defecto de Traefik — no verificado)
  - `localhost:30500` (NodePort directo del Service, fallback)
- **Almacenamiento**: PVC local
- **Scripts**:
  - `scripts/02-setup-registry.sh` — Deployment + Service + PVC
  - `scripts/05-setup-registry-ingress.sh` — IngressRoute Traefik, hosts file, daemon.json, k3s registries.yaml
- **Estado**: ✅ Instalado y verificado (push + pull funcionando con imágenes del L1 Copilot)

#### Cómo lo usa Docker (host) y k3s (cluster)

Para que `registry.local` funcione **sin puerto y sin TLS válido**, se han configurado:

| Componente | Fichero | Contenido relevante |
|-----------|--------|--------------------|
| Docker daemon (Rancher) | `/etc/docker/daemon.json` (dentro de la WSL `rancher-desktop`) | `"insecure-registries": ["registry.local"]` |
| k3s containerd | `/etc/rancher/k3s/registries.yaml` | mirror y `tls.insecure_skip_verify: true` para `registry.local` |
| Resolución DNS (host Windows) | `C:\Windows\System32\drivers\etc\hosts` | `127.0.0.1 registry.local` |
| Resolución DNS (cluster k3s) | `/etc/hosts` dentro de la WSL `rancher-desktop` | `127.0.0.1 registry.local` |
| Routing en Traefik | `IngressRoute registry/registry` | `Host(registry.local)` → svc `registry:5000`, entrypoints `web` y `websecure`, `tls: {}` |

> ⚠️ **Persistencia tras reset de Rancher Desktop**: `daemon.json`, `registries.yaml` y `/etc/hosts` viven dentro de la WSL `rancher-desktop` y se regeneran si haces *Reset Kubernetes* o *Factory Reset*. Vuelve a ejecutar `scripts/05-setup-registry-ingress.sh`.

#### Comandos de prueba

```bash
# Catálogo del registry (3 imágenes esperadas tras el primer deploy)
curl -sk https://registry.local/v2/_catalog
# {"repositories":["hello-world","l1-copilot-backend","l1-copilot-frontend"]}

# Push manual
docker tag hello-world:latest registry.local/hello-world:test
docker push registry.local/hello-world:test

# Lo que ve k3s al pull (desde dentro de la WSL rancher-desktop)
wsl -d rancher-desktop crictl pull registry.local/hello-world:test
```

---

## Estructura del repositorio

```
infra/
├── INFRA.md                                # Este documento
├── namespace-l1-copilot.yaml               # Namespace de la app
├── values-l1-copilot-backend.yaml          # Values Helm del backend
├── values-l1-copilot-frontend.yaml         # Values Helm del frontend
├── .env.l1-copilot                         # Defaults compartidos (versionado)
├── .env.l1-copilot.local                   # Overrides locales (no versionado, p.ej. GEMINI_API_KEY)
├── charts/
│   └── generic-app/                        # Chart Helm reusable Deployment+Service+Ingress
└── scripts/
    ├── 01-install-rancher-desktop.sh       # Rancher Desktop (desde WSL2)
    ├── 02-setup-registry.sh                # Registry local en k3s (Deploy + Svc + PVC)
    ├── 03-setup-postgres.sh                # PostgreSQL 16 Alpine en namespace infra
    ├── 04-setup-postgres-ingress.sh        # Traefik TCP -> postgres.local:5432 (+ NodePort 15432)
    ├── 05-setup-registry-ingress.sh        # IngressRoute registry.local + daemon.json + k3s registries.yaml
    ├── 06-setup-app-ingress.sh             # Ingress único http://l1-copilot.local (frontend + /api backend)
    ├── 06-setup-backend-ingress.sh         # [DEPRECATED] sustituido por 06-setup-app-ingress.sh
    ├── 07-setup-frontend-ingress.sh        # [DEPRECATED] sustituido por 06-setup-app-ingress.sh
    ├── deploy.sh                           # Build + push + secrets + helm upgrade (idempotente)
    ├── run-sh-gitbash.cmd                  # Wrapper: lanza un .sh con Git Bash de Windows
    ├── create-secrets.ps1                  # Crea Secrets de K8s desde un .env (uso manual genérico)
    ├── add-copilot-host.ps1                # (Admin) hosts: l1-copilot.local
    ├── add-postgres-host.ps1               # (Admin) hosts: postgres.local
    └── add-registry-host.ps1               # (Admin) hosts: registry.local
```

> En la raíz del repo:
> - `scripts/setup-l1-copilot-db.sh` crea la BD `l1_copilot` en el Postgres compartido y aplica schema + seed.
> - `scripts/seed-l1-copilot-db.sh` recarga sólo el seed (idempotente). Vía psql nativo de Windows.
> - `scripts/start.sh` / `stop.sh` (y sus equivalentes `.bat`) arrancan/paran backend + frontend en local (sin K8s).

---

## Requisitos previos

- Windows 10/11 64-bit con WSL2 (Ubuntu)
- Al menos 8 GB de RAM recomendados (mínimo 4 GB)
- winget disponible

---

## Notas

- El registry local solo consume recursos durante push/pull de imágenes.
- Traefik gestiona el ingress sin configuración adicional.
- Desde dentro del clúster, k3s pulla por `registry.local` (resuelto por `/etc/hosts` de la WSL `rancher-desktop` y enrutado por Traefik a `svc/registry:5000`).

---

## Despliegue del L1 Support Copilot

### Routing `/api` → backend, `/` → frontend (mismo host)

El frontend Angular y el backend Spring Boot comparten host (`l1-copilot.local`). El reparto es por path:

| URL externa | Llega a | Cómo |
|------------|---------|------|
| `http://l1-copilot.local/` | Frontend nginx | Ingress `/` (pathType Prefix) → `l1-copilot-frontend-generic-app:80` |
| `http://l1-copilot.local/api/**` | Backend Spring | Ingress `/api` (pathType Prefix) → `l1-copilot-backend-generic-app:8080` |

El Ingress envía el path **sin reescribir**. El prefijo `/api` lo absorbe el backend vía `server.servlet.context-path: /api` en [`backend/src/main/resources/application.yml`](../backend/src/main/resources/application.yml). Por eso los `@RequestMapping` de los controllers no llevan `/api` (sería duplicado): `@RequestMapping("/copilot")` se sirve en `http://l1-copilot.local/api/copilot/...`. El actuator queda automáticamente en `/api/actuator/health` y las probes del Deployment ya apuntan ahí.

> Ventaja: ni Traefik necesita un middleware `StripPrefix`, ni el frontend tiene que reescribir URLs. Si algún día se moviera el back a otro host, basta con cambiar `context-path` o el Ingress, sin tocar código de controllers.

### Script único: `infra/scripts/deploy.sh`

Un único script bash parametrizable que orquesta el ciclo completo (namespace → Secrets → docker build/push → helm upgrade --install) sobre el chart genérico `infra/charts/generic-app`, usando los values files:

- `infra/values-l1-copilot-backend.yaml`
- `infra/values-l1-copilot-frontend.yaml`

```bash
# Desde la raíz del repo (WSL o Git Bash):
./infra/scripts/deploy.sh --component backend            # build + secrets + helm
./infra/scripts/deploy.sh --component frontend --tag 1.0.1
./infra/scripts/deploy.sh --component all
./infra/scripts/deploy.sh --component backend --skip-build --skip-secrets
./infra/scripts/deploy.sh --component backend --dry-run  # helm template, no aplica
./infra/scripts/deploy.sh --uninstall                    # limpia todo el namespace
```

Run configs equivalentes en IntelliJ (`.run/`):
- **Deploy - Backend** → `infra/scripts/deploy.sh --component backend`
- **Deploy - Frontend** → `infra/scripts/deploy.sh --component frontend`
- **Reset DB (seed)** → `scripts/seed-l1-copilot-db.sh`

Todas pasan por el wrapper `infra/scripts/run-sh-gitbash.cmd`, que invoca el `.sh` con el Git Bash de Windows (no WSL — necesitamos los .exe de Rancher Desktop y psql nativos). Detalles del wrapper:

- Intérprete del Run Config: `C:\Windows\System32\cmd.exe /c`.
- `run-sh-gitbash.cmd` busca `bash.exe` en rutas conocidas (Git for Windows o cmder), convierte `\` → `/` en los argumentos y ejecuta `bash -c "<script> <args>"`.
- Razón: IntelliJ Sh plugin teclea el comando en una terminal PowerShell por defecto. PowerShell no ejecuta `.sh`, y el path de `pwsh.exe` rompe por los espacios cuando IntelliJ aplica escaping Unix. `cmd.exe` no tiene espacios en su path y `cmd /c <wrapper>` evita el problema.

### Fuente de verdad única para Secrets (local + K8s)

Dos ficheros en `infra/`:

| Fichero | Commiteado | Contenido |
|---------|------------|-----------|
| `.env.l1-copilot` | ✅ Sí (excepción explícita en `.gitignore`) | `POSTGRES_USER`, `POSTGRES_PASSWORD` (ya públicos en INFRA.md), `GEMINI_API_KEY=` vacío |
| `.env.l1-copilot.local` | ❌ No (gitignored por patrón `.env.*`) | Override con tu `GEMINI_API_KEY` real |

**Bootstrap inicial**:

```bash
cp infra/.env.l1-copilot.local.example infra/.env.l1-copilot.local
# editar el fichero y poner tu key de https://aistudio.google.com/app/apikey
```

### Flujo Secret → variable de entorno

```
.env.l1-copilot  +  .env.l1-copilot.local           (fuente de verdad)
        │
        ▼  deploy.sh → sync_secrets()
kubectl create secret generic <secret> --dry-run=client -o yaml | kubectl apply
        │
        ▼  helm upgrade --install (chart generic-app)
Deployment.spec.containers[].env[].valueFrom.secretKeyRef
        │
        ▼  pod en running
La env var aparece en el contenedor: $GEMINI_API_KEY, $SPRING_DATASOURCE_PASSWORD, …
```

Mapping configurado en `deploy.sh`:

```bash
declare -rA SECRETS_BACKEND=(
  [gemini-api-key]="GEMINI_API_KEY"
  [postgres-credentials]="POSTGRES_USER POSTGRES_PASSWORD"
)
```

Añadir un Secret nuevo = añadir una entrada al map + declarar las claves en `.env.l1-copilot` (o su `.local`). El chart ya consume el Secret vía `secretEnv` en el values file correspondiente.

### Backend local (Spring Boot) usando las mismas variables

El perfil `dev` solo necesita `GEMINI_API_KEY` (Postgres queda en H2). Tres formas de inyectarla en IntelliJ — usa la que prefieras:

1. **Plugin [EnvFile](https://plugins.jetbrains.com/plugin/7861-envfile)** (recomendado): en `.run/Backend.run.xml` añade `infra/.env.l1-copilot` y `infra/.env.l1-copilot.local` como ficheros de entorno. Mismo source de verdad que el deploy.
2. **Variables en la run config**: edita `.run/Backend.run.xml` y añade `<env name="GEMINI_API_KEY" value="..." />` (queda commiteada — solo si te da igual).
3. **Shell**: exporta antes de arrancar IntelliJ — `source infra/.env.l1-copilot && source infra/.env.l1-copilot.local`.

### Estrategia de Secrets: evolución futura

La función `sync_secrets()` del script aísla todo el manejo de credenciales. Migrar a otra estrategia (SOPS+age, Sealed Secrets, External Secrets + Vault) **solo toca esa función**; el resto del flujo (namespace, chart, values, run configs) no cambia.
