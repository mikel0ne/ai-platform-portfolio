# Multi-stage build shared by every Spring Boot backend in the repo; infra/scripts/deploy.sh passes the
# project's build context (infra/projects/<project>.env -> CONTEXT_BACKEND). platform-commons comes in as the
# BuildKit named context "commons" because it lives outside the per-app context.
#   docker build --build-context commons=platform-commons -f docker/backend.Dockerfile \
#     -t registry.local/<project>-backend:<tag> ./autoops/backend

FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# The wrapper pins the Maven version; the pom goes first so the dependency layer stays cached between builds.
COPY mvnw pom.xml lombok.config ./
COPY .mvn .mvn

# Installed before the app's dependency:go-offline so the app pom resolves it from the image's local repository.
COPY --from=commons . /commons
RUN sh ./mvnw -B -q -f /commons/pom.xml install -DskipTests

RUN sh ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN sh ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
USER 1000

# One layer per Spring Boot layer: a code change only rebuilds the last, small one.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

# deploy.sh always creates corpus/ in the build context (empty for projects without a local corpus).
COPY corpus /app/corpus

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
