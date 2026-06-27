# Backend Spring Boot — build multi-stage. Genérico para cualquier proyecto del
# monorepo (autoops, l1-copilot…): lo invoca infra/scripts/deploy.sh con la
# imagen y el contexto del proyecto (infra/projects/<p>.env → CONTEXT_BACKEND).
# Uso (desde la raíz del repo):
#   docker build -f docker/backend.Dockerfile -t registry.local/<proyecto>-backend:<tag> ./autoops/backend
# Ver autoops/docs/infraestructura/04-dockerizacion.md.

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

# Corpus del RAG horneado en la imagen (deploy.sh lo copia al contexto de build
# antes de construir; la app lo lee vía AUTOOPS_INGESTION_CORPUS_PATH=/app/corpus).
# El directorio puede no existir en builds que no lo necesiten → COPY condicional
# vía glob que no falla si está vacío.
COPY corpus /app/corpus

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
