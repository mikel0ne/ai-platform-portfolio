# Multi-stage build shared by every Spring Boot backend in the repo; infra/scripts/deploy.sh passes the
# project's build context (infra/projects/<project>.env -> CONTEXT_BACKEND).
#   docker build -f docker/backend.Dockerfile -t registry.local/<project>-backend:<tag> ./autoops/backend

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# The pom goes first so the dependency layer stays cached between builds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
USER 1000

COPY --from=build /workspace/target/*.jar app.jar

# deploy.sh always creates corpus/ in the build context (empty for projects without a local corpus).
COPY corpus /app/corpus

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
