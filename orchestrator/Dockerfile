# syntax=docker/dockerfile:1
# Orchestrator image for the local Docker Compose stack (ADR 0007).
#
# Three stages: build the jar with the Maven Wrapper, extract it into layers, run on a JRE as a non-root user.
# Layers go from least to most frequently changed, so a code change rebuilds only the last one.
# Tests are skipped here: they need Docker and run in CI (orchestrator workflow) and with ./mvnw verify.

# ---------------------------------------------------------------- build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src/main src/main
# The cache mount keeps the Maven distribution and the local repository between builds, so dependencies are not
# downloaded again on every code change; it is not part of the image.
# maven.test.skip also skips compiling the tests, which are excluded from the build context (.dockerignore).
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package \
    && cp target/provisio-orchestrator-*.jar application.jar

# ---------------------------------------------------------------- extract layers
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination layers

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 provisio \
    && useradd --system --uid 10001 --gid provisio --no-create-home provisio
WORKDIR /application
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract /extract/layers/application/ ./
# Numeric, so tools that check for a non-root user can verify it without reading /etc/passwd
USER 10001:10001
# Heap sized from the container memory limit (mem_limit in docker-compose.yml), not from the host's RAM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
