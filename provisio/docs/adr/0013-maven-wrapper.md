# 0013 — Maven with Maven Wrapper for the Java build

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

The orchestrator needs a build tool. The build must be identical on a developer machine, in CI and inside Docker.

## Considered options

1. Gradle (with wrapper).
2. Maven (with wrapper).

## Decision outcome

Maven with the **Maven Wrapper** (`./mvnw`):

- Maven is the de facto standard in enterprise Java and in the Camunda and Spring ecosystems, which makes the
  project immediately readable for reviewers. Gradle's incremental-build speed brings no real benefit at this size.
- The wrapper pins the Maven version in the repository: no local installation needed, same version everywhere.
- Script-only wrapper: only `.mvn/wrapper/maven-wrapper.properties` is committed, no binary jar.
- Docker multi-stage builds and CI use `./mvnw`, never a separately versioned Maven image.
- A **single Maven module** for the orchestrator, consistent with [ADR 0010](0010-single-spring-boot-application.md).

## Consequences

### Positive

- Reproducible builds; familiar structure for Java reviewers.

### Negative

- Slower builds than Gradle at large scale (not relevant here).
