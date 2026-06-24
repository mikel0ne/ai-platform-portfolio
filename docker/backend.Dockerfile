# Backend Spring Boot + LangGraph4j — build multi-stage.
# Uso (desde la raíz del repo):
#   docker build -f docker/backend.Dockerfile -t autoops-mcp:0.1.0 ./backend
# Ver docs/infraestructura/04-dockerizacion.md.

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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
