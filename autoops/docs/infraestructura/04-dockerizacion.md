# 04 — Dockerización

> **Son DOS imágenes**, no una: `registry.local/autoops-backend` (Spring Boot, build multi-stage Maven) y
> `registry.local/autoops-frontend` (Angular construido con `npm` y servido por nginx). Los Dockerfiles son
> **compartidos** por todo el monorepo: `docker/backend.Dockerfile` y `docker/frontend.Dockerfile`. El corpus
> del RAG se copia a la imagen del backend (`deploy.sh` lo copia al contexto desde `CORPUS_DIR`), aunque en el
> clúster no se lee (ver §1).

Ninguna de las dos imágenes se construye a mano con `docker build`: las orquesta el script genérico
`infra/scripts/deploy.sh --project autoops --component backend|frontend|all` (ver `infra/projects/autoops.env`
para los paths exactos: `DOCKERFILE_BACKEND`/`CONTEXT_BACKEND`, `DOCKERFILE_FRONTEND`/`CONTEXT_FRONTEND`,
`CORPUS_DIR`).

Convención de etiquetado: `registry.local/autoops-<componente>:<tag>` (SemVer manual). Push al registry local
vía `--tag`; `imagePullPolicy: IfNotPresent`. **`--tag` único por build** (mismo tag = el pod no se actualiza,
ver §3).

## 1. Dockerfile del backend

`docker/backend.Dockerfile` (contexto de build: `autoops/backend/`; la librería `platform-commons` entra como
contexto con nombre de BuildKit: `--build-context commons=platform-commons`, que `deploy.sh` ya pasa). Extracto:

```dockerfile
# ---- Stage 1: build ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# El wrapper fija la versión de Maven; el pom va primero para que la capa de dependencias se cachee
COPY mvnw pom.xml lombok.config ./
COPY .mvn .mvn

# platform-commons se instala antes, para que el pom de la app lo resuelva del repositorio local de la imagen
COPY --from=commons . /commons
RUN sh ./mvnw -B -q -f /commons/pom.xml install -DskipTests

RUN sh ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN sh ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
USER 1000

# Una capa por capa de Spring Boot: un cambio de código solo reconstruye la última, pequeña
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
COPY corpus /app/corpus

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Notas:

- Multi-stage con el jar en capas (`layertools`/`jarmode=tools`): imagen final ligera (`eclipse-temurin:17-jre-alpine`,
  sin JDK ni Maven) y usuario no-root (`uid 1000`).
- No hay UI dentro del jar: la SPA Angular es la **otra** imagen (frontend).
- **Charset**: el despliegue pasa `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8` por el
  ConfigMap; el backend (`CharsetGuard`) no arranca si el charset por defecto de la JVM no es UTF-8.
- **Corpus**: `deploy.sh` copia `CORPUS_DIR` (`autoops/corpus`) a `autoops/backend/corpus` *antes* del build, así
  que `COPY corpus /app/corpus` siempre encuentra una carpeta. En el clúster, sin embargo, no se lee:
  `autoops.ingestion.corpus-path` toma por defecto `../corpus` (no existe en la imagen) y el ConfigMap no lo
  sobrescribe, así que la fuente local queda desactivada y la ingesta sale de GitHub y de Wiki.js (guía 07 §3). Para
  usar el corpus horneado habría que definir `AUTOOPS_INGESTION_CORPUS_PATH=/app/corpus`.

## 2. Dockerfile del frontend

`docker/frontend.Dockerfile` (contexto: `autoops/frontend/`; genérico para cualquier proyecto Angular del
monorepo, no solo AutoOps):

```dockerfile
# ---- Stage 1: build ----
FROM node:24-alpine AS build
WORKDIR /workspace

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Stage 2: runtime ----
# Variante sin privilegios: corre como usuario no root en el 8080, así que el pod puede fijar runAsNonRoot
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /workspace/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
```

`deploy.sh` copia `docker/frontend-nginx.conf` a `autoops/frontend/nginx.conf` antes del build (logging JSON
estructurado para Loki/Grafana, ver guía 07). El Service del chart expone el puerto 80 hacia el 8080 del contenedor.

## 3. Construcción y push (automatizado)

```bash
./infra/scripts/deploy.sh --project autoops --component all --tag 0.4.0
```

- El script hace `docker build` + `docker push registry.local/autoops-<componente>:<tag>` por cada componente,
  y luego `helm upgrade --install` (chart `infra/charts/generic-app`, guía 05) con `--set image.tag=<tag>`.
- `--skip-build` salta el build/push y solo aplica Helm (útil si solo cambió un `values-autoops-*.yaml`).
- `--dry-run` renderiza el chart con `helm template` sin aplicar nada al clúster.
- El registry `registry.local` es el del clúster (`infra/scripts/02-setup-registry.sh`, `05-setup-registry-ingress.sh`):
  k3s lo resuelve y no hace falta cargar imágenes a mano.

## 4. Ciclo de actualización

`kubectl set image` no se usa a mano: el ciclo completo es `deploy.sh --tag <nuevo>` (rebuild + push + `helm
upgrade`, que ya gestiona el `RollingUpdate`).

> **Evitar `:latest`** y reutilizar etiquetas: con `IfNotPresent`, Kubernetes no re-descarga una etiqueta que ya
> conoce y se desplegaría código viejo. Etiqueta nueva por build = despliegues deterministas.

Continuar con [05-despliegue-servicios.md](05-despliegue-servicios.md).
