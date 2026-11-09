# 04 — Dockerización

> **Son DOS imágenes**, no una: `registry.local/autoops-backend` (Spring Boot, build multi-stage Maven) y
> `registry.local/autoops-frontend` (Angular construido con `npm` y servido por nginx). Los Dockerfiles son
> **compartidos** por todo el monorepo: `docker/backend.Dockerfile` y `docker/frontend.Dockerfile`. El corpus
> del RAG se hornea en la imagen del backend (`deploy.sh` lo copia al contexto desde `CORPUS_DIR`).

Ninguna de las dos imágenes se construye a mano con `docker build`: las orquesta el script genérico
`infra/scripts/deploy.sh --project autoops --component backend|frontend|all` (ver `infra/projects/autoops.env`
para los paths exactos: `DOCKERFILE_BACKEND`/`CONTEXT_BACKEND`, `DOCKERFILE_FRONTEND`/`CONTEXT_FRONTEND`,
`CORPUS_DIR`).

Convención de etiquetado: `registry.local/autoops-<componente>:<tag>` (SemVer manual). Push al registry local
vía `--tag`; `imagePullPolicy: IfNotPresent`. **`--tag` único por build** (mismo tag = el pod no se actualiza,
ver §3).

## 1. Dockerfile del backend

`docker/backend.Dockerfile` (contexto de build: `autoops/backend/`):

```dockerfile
# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Primero solo el pom: la capa de dependencias se cachea entre builds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
USER 1000

COPY --from=build /workspace/target/*.jar app.jar
COPY corpus /app/corpus    # horneado por deploy.sh antes del build (ver §1 más abajo)

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Notas:

- Multi-stage simple (jar único, sin `layertools`): imagen final ligera (`eclipse-temurin:17-jre-alpine`, sin
  JDK ni Maven) y usuario no-root (`uid 1000`).
- No hay UI dentro del jar: la SPA Angular es la **otra** imagen (frontend).
- **Corpus horneado**: `deploy.sh` copia `CORPUS_DIR` (`autoops/corpus`) a `autoops/backend/corpus` *antes* del
  build, así que `COPY corpus /app/corpus` siempre encuentra contenido; la app lo lee vía
  `AUTOOPS_INGESTION_CORPUS_PATH=/app/corpus` (ConfigMap del chart, guía 05).

## 2. Dockerfile del frontend

`docker/frontend.Dockerfile` (contexto: `autoops/frontend/`; genérico para cualquier proyecto Angular del
monorepo, no solo AutoOps):

```dockerfile
# ---- Stage 1: build ----
FROM node:20-alpine AS build
WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Stage 2: runtime ----
FROM nginx:1.27-alpine

# Angular 17+ deja la app en dist/<nombre>/browser
COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
```

`deploy.sh` copia `docker/frontend-nginx.conf` a `autoops/frontend/nginx.conf` antes del build (logging JSON
estructurado para Loki/Grafana, ver guía 07).

## 3. Construcción y push (automatizado)

```bash
./infra/scripts/deploy.sh --project autoops --component all --tag 0.4.0
```

- El script hace `docker build` + `docker push registry.local/autoops-<componente>:<tag>` por cada componente,
  y luego `helm upgrade --install` (chart `infra/charts/generic-app`, guía 05) con `--set image.tag=<tag>`.
- `--skip-build` salta el build/push y solo aplica Helm (útil si solo cambió un `values-autoops-*.yaml`).
- `--dry-run` renderiza el chart con `helm template` sin aplicar nada al clúster.
- El registry `registry.local` resuelve dentro del clúster gracias al `containerd-snapshotter` compartido con
  dockerd (k3s/Rancher Desktop): no hace falta `minikube image load` ni equivalentes.

## 4. Ciclo de actualización

`kubectl set image` no se usa a mano: el ciclo completo es `deploy.sh --tag <nuevo>` (rebuild + push + `helm
upgrade`, que ya gestiona el `RollingUpdate`).

> **Evitar `:latest`** y reutilizar etiquetas: con `IfNotPresent`, Kubernetes no re-descarga una etiqueta que ya
> conoce y se desplegaría código viejo. Etiqueta nueva por build = despliegues deterministas.

Continuar con [05-despliegue-servicios.md](05-despliegue-servicios.md).
