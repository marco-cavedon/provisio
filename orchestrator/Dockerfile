# syntax=docker/dockerfile:1
# Orchestrator image for the local Docker Compose stack (ADR 0007).
#
# Three stages: build the jar with the Maven Wrapper, extract it into layers, run on a JRE as a non-root user.
# Layers go from least to most frequently changed, so a code change rebuilds only the last one.
# Tests are skipped here: they need Docker and run in CI (orchestrator workflow) and with ./mvnw verify.
#
# Multi-platform (linux/amd64, linux/arm64; release workflow): the jar is platform-independent bytecode, so the build
# and extract stages run once on the builder's native platform ($BUILDPLATFORM). Only the runtime stage is built per
# target platform, and its only RUN step is the user creation, so emulation never runs Maven.
#
# Base images are pinned to an exact Temurin release and Ubuntu base, so the same Dockerfile builds the same image
# over time; Dependabot proposes the updates (.github/dependabot.yml). All three stages use the same release.

# ---------------------------------------------------------------- build
FROM --platform=$BUILDPLATFORM eclipse-temurin:26.0.2_10-jdk-resolute AS build
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
FROM --platform=$BUILDPLATFORM eclipse-temurin:26.0.2_10-jre-resolute AS extract
WORKDIR /extract
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination layers

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:26.0.2_10-jre-resolute
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
