# 04 — Dockerización

> **Son DOS imágenes**, no una: `registry.local/autoops-backend` (Spring Boot, build multi-stage Maven) y
> `registry.local/autoops-frontend` (Angular construido con `npm` y servido por nginx). Los Dockerfiles son
> **compartidos** por todo el monorepo: `docker/backend.Dockerfile` y `docker/frontend.Dockerfile`. El corpus
> del RAG se hornea en la imagen del backend (`deploy.sh` lo copia al contexto desde `CORPUS_DIR`).

`autoops-backend` se empaqueta con un **build multi-stage** para producir una imagen pequeña sin requerir Java
instalado en quien construye (solo Docker/nerdctl).

Convención de etiquetado: `registry.local/autoops-<componente>:<versión>` (SemVer manual). Push al registry
local; `imagePullPolicy: IfNotPresent`. **Pasa siempre un `--tag` único por build** (mismo tag = el pod no se
actualiza). Lo orquesta `infra/scripts/deploy.sh --project autoops --component backend|frontend|all`.

## 1. Dockerfile

`Dockerfile` (en la raíz del proyecto):

```dockerfile
# ---------- Etapa 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cachear dependencias: primero solo el pom
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Extraer capas de Spring Boot para mejor caché de imagen
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Usuario no root
RUN addgroup -S autoops && adduser -S autoops -G autoops
USER autoops

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

Notas:

- La UI estática del chat vive en `src/main/resources/static/` y queda **dentro del jar**: no hay imagen de
  frontend separada ni Nginx.
- El **corpus** (`corpus/*.md`) puede empaquetarse en la imagen (montado como classpath o copiado a `/app/corpus`)
  o montarse como volumen/ConfigMap; la ingesta se lanza tras el despliegue (guía 07 §3).
- `dependency:go-offline` no resuelve el 100 % de los plugins, pero acelera los rebuilds.

## 2. Construcción de la imagen

Desde la raíz del proyecto:

```powershell
docker build -t autoops/mcp:0.1.0 .
docker images "autoops/*"
```

## 3. Hacer visible la imagen al clúster

### Docker Desktop

Nada que hacer: el clúster comparte daemon con `docker build`. Con `IfNotPresent`, Kubernetes usa la imagen local.

### Minikube

```powershell
minikube image load autoops/mcp:0.1.0
# o construir dentro del daemon de minikube:
# minikube docker-env | Invoke-Expression ; docker build -t autoops/mcp:0.1.0 .
```

## 4. Ciclo de actualización

```powershell
docker build -t autoops/mcp:0.1.1 .                  # nueva etiqueta por build
# (minikube image load si aplica)
kubectl set image deployment/autoops-mcp mcp=autoops/mcp:0.1.1 -n autoops
kubectl rollout status deployment/autoops-mcp -n autoops
```

> **Evitar `:latest`** y reutilizar etiquetas: con `IfNotPresent`, Kubernetes no re-descarga una etiqueta que ya
> conoce y se desplegaría código viejo. Etiqueta nueva por build = despliegues deterministas.

Continuar con [05-despliegue-servicios.md](05-despliegue-servicios.md).
