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
| Base de datos relacional | PostgreSQL 16 Alpine (namespace `infra`, compartida) | ✅ Instalado |
| Base de datos vectorial | Weaviate 1.27 (namespace `infra`, compartida) | 🆕 `06-setup-weaviate.sh` |
| Observabilidad (logs) | Loki + Promtail + Grafana (namespace `observability`) | 🆕 `07-setup-observability.sh` |
| Apps back/front | Chart `generic-app` + `deploy.sh --project <p>` | ✅ l1-copilot · 🆕 autoops |

> **Criterio de recursos compartidos:** una sola instancia por motor (Postgres y
> Weaviate) en el namespace `infra`; cada proyecto crea su BD/clase. El stack de
> logs es cluster-wide en `observability` (recoge todos los namespaces). El
> despliegue de apps es **genérico por proyecto** (`deploy.sh --project autoops|l1-copilot`),
> con descriptores en `infra/projects/<project>.env`.

---

## Componentes instalados

### 1. Rancher Desktop

- **Versión**: última estable
- **Kubernetes**: k3s
- **Runtime de contenedores**: dockerd (compatibilidad Docker CLI)
- **Ingress**: Traefik (incluido)
- **Script**: `scripts/01-install-rancher-desktop.ps1` (PowerShell nativo, vía winget — preferido,
  evita depender de WSL2) o `scripts/01-install-rancher-desktop.sh` (mismo winget, pero invocado
  desde WSL2; solo para este paso puntual, antes de que exista Git Bash con docker/kubectl en el PATH)
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

### 5. Weaviate (base vectorial compartida)

- **Imagen**: `cr.weaviate.io/semitechnologies/weaviate:1.27.0`
- **Namespace**: `infra` (mismo criterio que Postgres: una instancia para todo el portfolio)
- **Persistencia**: PVC `weaviate-data` de 5 Gi
- **Sin vectorizador** (`DEFAULT_VECTORIZER_MODULE=none`): cada app trae sus vectores. AutoOps los calcula con Gemini `text-embedding-004` (768 dims).
- **Sin auth** (`AUTHENTICATION_ANONYMOUS_ENABLED=true`) — solo local.
- **Manifiesto**: `components/weaviate/weaviate.yaml` · **Script**: `scripts/06-setup-weaviate.sh`
- **DNS interno**: `weaviate.infra.svc.cluster.local` (HTTP `8080`, gRPC `50051`)
- **Modelo de uso**: cada proyecto crea **su propia clase** (no se comparte el índice). AutoOps → `AutoopsDocChunk` (la crea la app al arrancar, `WeaviateGateway`).

```bash
bash infra/scripts/06-setup-weaviate.sh
# Debug desde el host:
kubectl port-forward svc/weaviate 8088:8080 -n infra
# http://localhost:8088/v1/schema
```

---

### 6. Observabilidad — logs (Loki + Promtail + Grafana)

- **Chart**: `grafana/loki-stack` · **Namespace**: `observability`
- **Componentes**: Loki (almacén), Promtail (DaemonSet, recoge logs de **todos** los namespaces), Grafana (UI + datasource Loki preconfigurado).
- **Values**: `observability/loki-stack-values.yaml` · **Script**: `scripts/07-setup-observability.sh`
- **Acceso**: `http://grafana.local` (admin/admin), ingress Traefik en `observability/grafana-ingress.yaml`.
- **Retención**: 7 días (entorno local).

```bash
bash infra/scripts/07-setup-observability.sh
# Resolver el host (Admin, una vez):
#   infra/scripts/add-host.ps1 -Hostname grafana.local
# En Grafana → Explore → Loki:  {namespace="autoops"} |= "ERROR"
```

> **Presupuesto de RAM** (Docker Desktop ≥ 6 GB): Postgres ~0,3 GB · Weaviate ~0,5 GB · Loki+Promtail+Grafana ~0,6 GB · cada app Spring ~0,6 GB · Traefik ~0,1 GB. Por eso se eligió Loki/Grafana en vez de ELK (Elasticsearch solo pediría 1-2 GB de heap).

---

## Estructura del repositorio (monorepo)

```
<repo>/                         # un solo repo/rama con la plataforma + un dir por proyecto
├── infra/                      # TODO lo cross-project (charts, scripts, manifests compartidos)
├── docker/                     # Dockerfiles genéricos (backend Java, frontend nginx) — compartidos
├── .run/                       # Run configs IntelliJ (prefijo por proyecto: «autoops · …»)
├── scripts/                    # scripts generales (start/stop local)
├── llms-*.txt · CLAUDE.md · README.md
├── autoops/                    # proyecto AutoOps MCP
│   ├── backend/  frontend/  docs/  corpus/
└── l1-copilot/                 # proyecto L1 Support Copilot
    ├── backend/  frontend/  docs/  scripts/
```

> Los descriptores `infra/projects/<p>.env` fijan el contexto de build de cada
> proyecto (`autoops/backend`, `l1-copilot/backend`…). Cambiar de layout = cambiar
> esa línea; `deploy.sh` no se toca.

### Contenido de `infra/`

```
infra/
├── INFRA.md                                # Este documento
├── projects/                               # Descriptores por proyecto (lo que varía)
│   ├── l1-copilot.env                      #   namespace, imágenes, secrets, rutas ingress
│   └── autoops.env
├── namespace-l1-copilot.yaml               # Namespace por proyecto (convención namespace-<p>.yaml)
├── namespace-autoops.yaml
├── rbac-autoops.yaml                       # RBAC read-only (convención rbac-<p>.yaml, opcional)
├── values-l1-copilot-backend.yaml          # Values Helm (convención values-<p>-<componente>.yaml)
├── values-l1-copilot-frontend.yaml
├── values-autoops-backend.yaml
├── values-autoops-frontend.yaml
├── .env.l1-copilot / .env.autoops          # Defaults compartidos (versionado)
├── .env.local                              # GEMINI_API_KEY real, compartida por TODOS los proyectos (no versionado)
├── .env.<p>.local                          # Override puntual de UN proyecto, opcional (no versionado)
├── charts/
│   └── generic-app/                        # Chart Helm reusable Deployment+Service+Ingress
├── components/
│   └── weaviate/weaviate.yaml              # Weaviate compartido (ns infra)
├── observability/
│   ├── loki-stack-values.yaml              # Values del chart grafana/loki-stack
│   └── grafana-ingress.yaml                # Ingress Traefik grafana.local
└── scripts/
    ├── 01-install-rancher-desktop.ps1      # Rancher Desktop vía winget (preferido, sin WSL2)
    ├── 01-install-rancher-desktop.sh       # Idem, invocado desde WSL2
    ├── 02-setup-registry.sh                # Registry local en k3s (Deploy + Svc + PVC)
    ├── 03-setup-postgres.sh                # PostgreSQL 16 Alpine en namespace infra
    ├── 04-setup-postgres-ingress.sh        # Traefik TCP -> postgres.local:5432 (+ NodePort 15432)
    ├── 05-setup-registry-ingress.sh        # IngressRoute registry.local + daemon.json + k3s registries.yaml
    ├── 06-setup-weaviate.sh                # Weaviate compartido (ns infra)
    ├── 07-setup-observability.sh           # Loki + Promtail + Grafana (ns observability)
    ├── deploy.sh                           # GENÉRICO: deploy.sh --project <p> --component <c>
    ├── setup-app-ingress.sh                # GENÉRICO: ingress por proyecto (--project <p>)
    ├── ingest-autoops.sh                   # Dispara la ingesta del corpus de AutoOps
    ├── run-sh-gitbash.cmd                  # Wrapper: lanza un .sh con Git Bash de Windows
    ├── create-secrets.ps1                  # Crea Secrets de K8s desde un .env (uso manual genérico)
    ├── add-host.ps1                        # (Admin) GENÉRICO: add-host.ps1 -Hostname <host>
    ├── add-copilot-host.ps1                # (Admin) hosts: l1-copilot.local
    ├── add-postgres-host.ps1               # (Admin) hosts: postgres.local
    └── add-registry-host.ps1               # (Admin) hosts: registry.local
```

> Scripts propios de cada proyecto (monorepo):
> - `l1-copilot/scripts/setup-l1-copilot-db.sh` crea la BD `l1_copilot` en el Postgres compartido (schema + seed).
> - `l1-copilot/scripts/seed-l1-copilot-db.sh` recarga sólo el seed (idempotente). Vía psql nativo de Windows.
> - `scripts/start.sh` / `stop.sh` (raíz, `.bat` incluidos) — scripts generales de arranque local.

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

### Script genérico: `infra/scripts/deploy.sh --project <p>`

Un único script bash **genérico** orquesta el ciclo completo (namespace → RBAC → Secrets → docker build/push → helm upgrade --install) sobre el chart `infra/charts/generic-app`. Lo específico de cada proyecto vive en `infra/projects/<project>.env`; los nombres de values/namespace/rbac/.env se derivan **por convención** del nombre de proyecto.

```bash
# Desde la raíz del repo (Git Bash de Windows):
./infra/scripts/deploy.sh --project autoops    --component backend
./infra/scripts/deploy.sh --project autoops    --component all --tag 0.1.1
./infra/scripts/deploy.sh --project l1-copilot --component frontend --tag 1.0.2
./infra/scripts/deploy.sh --project autoops    --component backend --dry-run
./infra/scripts/deploy.sh --project autoops    --uninstall

# Ingress por proyecto (tras desplegar back+front):
./infra/scripts/setup-app-ingress.sh --project autoops      # http://autoops.local
```

Run configs equivalentes en IntelliJ (`.run/`), todas vía `run-sh-gitbash.cmd`:
- **autoops · Deploy Backend / Frontend / All** → `deploy.sh --project autoops --component …`
- **autoops · Setup Ingress** → `setup-app-ingress.sh --project autoops`
- **autoops · Ingesta corpus** → `ingest-autoops.sh`
- **l1-copilot · Deploy Backend / Frontend** → `deploy.sh --project l1-copilot --component …`
- **infra · Setup Weaviate / Setup Observability** → `06-…` / `07-…`
- **autoops · Backend / Frontend** y **l1-copilot · Backend / Frontend / …** → run de desarrollo local (mvn/ng).
- **l1-copilot · Reset DB (seed)** → `l1-copilot/scripts/seed-l1-copilot-db.sh`

> **Trampa del tag fijo + `IfNotPresent`**: con `registry.local` local, redesplegar con el **mismo** tag no repulea la imagen ni recrea el pod. Pasa siempre un `--tag` único por build.

Todas pasan por el wrapper `infra/scripts/run-sh-gitbash.cmd`, que invoca el `.sh` con el Git Bash de Windows (no WSL — necesitamos los .exe de Rancher Desktop y psql nativos). Detalles del wrapper:

- Intérprete del Run Config: `C:\Windows\System32\cmd.exe /c`.
- `run-sh-gitbash.cmd` busca `bash.exe` en rutas conocidas (Git for Windows o cmder), convierte `\` → `/` en los argumentos y ejecuta `bash -c "<script> <args>"`.
- Razón: IntelliJ Sh plugin teclea el comando en una terminal PowerShell por defecto. PowerShell no ejecuta `.sh`, y el path de `pwsh.exe` rompe por los espacios cuando IntelliJ aplica escaping Unix. `cmd.exe` no tiene espacios en su path y `cmd /c <wrapper>` evita el problema.

### Fuente de verdad única para Secrets (local + K8s)

Tres ficheros en `infra/`, cada uno con un rol distinto:

| Fichero | Commiteado | Contenido |
|---------|------------|-----------|
| `.env.l1-copilot` / `.env.autoops` | ✅ Sí (excepción explícita en `.gitignore`) | claves del proyecto con placeholders (`.env.l1-copilot` también trae `POSTGRES_USER`/`POSTGRES_PASSWORD`, ya públicos en INFRA.md) |
| `.env.local` | ❌ No (gitignored por patrón `.env.*`) | tu `GEMINI_API_KEY` real — **una sola vez, compartida por TODOS los proyectos** del portfolio |
| `.env.<p>.local` | ❌ No (gitignored por patrón `.env.*`) | override puntual de UN proyecto (p.ej. rotar Postgres solo en local para l1-copilot); normalmente no hace falta |

**Bootstrap inicial** (una vez, sirve para todos los proyectos):

```bash
cp infra/.env.local.example infra/.env.local
# editar el fichero y poner tu key de https://aistudio.google.com/app/apikey
```

### Flujo Secret → variable de entorno

```
.env.<p>  +  .env.local  (+ .env.<p>.local si hace falta override)   (fuente de verdad)
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

Mapping configurado en el **descriptor del proyecto** (`infra/projects/<p>.env`):

```bash
# infra/projects/l1-copilot.env
declare -gA SECRETS_BACKEND=(
  [gemini-api-key]="GEMINI_API_KEY"
  [postgres-credentials]="POSTGRES_USER POSTGRES_PASSWORD"
)
# infra/projects/autoops.env  → solo la key de Gemini (Weaviate sin auth)
declare -gA SECRETS_BACKEND=( [autoops-llm-secret]="GEMINI_API_KEY" )
```

Añadir un Secret nuevo = añadir una entrada al map del descriptor + declarar la clave
correspondiente en `.env.<p>` (placeholder), `.env.local` (si es compartida entre
proyectos) o `.env.<p>.local` (si es específica de uno solo). El chart ya consume el
Secret vía `secretEnv` en el values file correspondiente.

### Backend local (Spring Boot) usando las mismas variables

El perfil `dev` solo necesita `GEMINI_API_KEY` (Postgres queda en H2). Tres formas de inyectarla en IntelliJ — usa la que prefieras:

1. **Plugin [EnvFile](https://plugins.jetbrains.com/plugin/7861-envfile)** (recomendado): en `.run/Backend.run.xml` añade `infra/.env.l1-copilot`, `infra/.env.local` y `infra/.env.l1-copilot.local` (en ese orden) como ficheros de entorno. Mismo source de verdad que el deploy.
2. **Variables en la run config**: edita `.run/Backend.run.xml` y añade `<env name="GEMINI_API_KEY" value="..." />` (queda commiteada — solo si te da igual).
3. **Shell**: exporta antes de arrancar IntelliJ — `source infra/.env.l1-copilot && source infra/.env.local && source infra/.env.l1-copilot.local`.

### Estrategia de Secrets: evolución futura

La función `sync_secrets()` del script aísla todo el manejo de credenciales. Migrar a otra estrategia (SOPS+age, Sealed Secrets, External Secrets + Vault) **solo toca esa función**; el resto del flujo (namespace, chart, values, run configs) no cambia.
