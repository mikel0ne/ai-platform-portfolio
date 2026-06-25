# 04 — Dockerización del servidor

`autoops-mcp` se empaqueta con un **build multi-stage** para producir una imagen pequeña y reproducible sin
requerir Java instalado en quien construye (solo Docker). Es **una sola imagen**: el chat de búsqueda es una UI
estática servida por la propia app, no un servicio aparte.

Convención de etiquetado: `autoops/mcp:<versión>` con SemVer manual (`0.1.0` para el MVP). En local no hay
registry: `imagePullPolicy: IfNotPresent`.

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
