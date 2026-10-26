# Orchestrator

Spring Boot application ([ADR 0010](../docs/adr/0010-single-spring-boot-application.md)), built with Maven Wrapper
([ADR 0013](../docs/adr/0013-maven-wrapper.md)) and organised as a **Spring Modulith** modular monolith with
domain-oriented modules ([ADR 0016](../docs/adr/0016-spring-modulith.md)).

It implements the agent's tools as Camunda job workers and exposes the BFF API — the only backend the frontend
talks to ([ADR 0011](../docs/adr/0011-frontend-only-talks-to-bff.md)). It is the only writer to MongoDB
([ADR 0006](../docs/adr/0006-precomputed-forecast-with-on-demand-recompute.md)).

## Requirements

- JDK 21 — run `mise install` in the repository root (see `mise.toml`). The build fails fast on other versions.
- Docker — integration tests start MongoDB in a container (Testcontainers).

## Build and test

```bash
./mvnw verify
```

- `ProvisioApplicationTests` — the application context assembles (no broker needed: job workers are disabled).
- `ModularityTests` — module boundaries are respected; module documentation is written to `target/spring-modulith-docs`.
- `MongoConfigurationTests` — the application refuses to start without a MongoDB password (no Docker needed).
- `MongoTransactionTests` — multi-document transactions commit and, above all, roll back against a real MongoDB
  (Docker required). Without a transaction manager, `@Transactional` would be silently ignored.

## Run

Requires the local stack running (`docker compose up -d` in the repository root): Camunda on ports 26500 and 8080,
MongoDB on port 27017.

The orchestrator connects to MongoDB as the `provisio` user and reads its password from the `MONGODB_APP_PASSWORD`
environment variable. Spring does not read `.env` by itself, and the application deliberately does not import it:
`.env` also holds credentials the orchestrator does not need.

The simplest way, from any directory:

```bash
orchestrator/run-local.sh            # extra arguments are passed to spring-boot:run
```

The script exports from `.env` only the variables listed in its `REQUIRED_VARS` (today `MONGODB_APP_PASSWORD`),
parsing the file rather than sourcing it, checks that `mongodb` and `orchestration` are healthy, then runs
`./mvnw spring-boot:run`. When `application.yaml` starts reading a new environment variable, add it to `REQUIRED_VARS`.

Without the script, pass the variable explicitly from the `orchestrator` directory:

```bash
MONGODB_APP_PASSWORD=$(grep '^MONGODB_APP_PASSWORD=' ../.env | cut -d= -f2-) ./mvnw spring-boot:run
```

In IntelliJ IDEA, set `MONGODB_APP_PASSWORD` in the run configuration's environment variables, or point the
EnvFile plugin to `.env`. Startup fails with "MongoDB password is not set" if the variable is missing: Spring's
configuration binding would otherwise pass the unresolved placeholder through as the password, and the error would
appear only at the first query, as an authentication failure (`MongoConfiguration`).

## Conventions

- **Job workers live in their domain module** (e.g. `inventory.worker`) and are named after the capability
  (`provisio.inventory.get-stock-level`), see [ADR 0019](../docs/adr/0019-job-workers-live-in-domain-modules.md).
- **Job worker classes are `public`**: the Camunda starter invokes worker methods reflectively from another package.
- **Tools return domain variables**; the BPMN maps them to `toolCallResult`. Workers do not know they serve an agent.
- **Tool errors are part of the tool contract**: business outcomes are returned as minimal structured data;
  technical failures fail the job so the engine retries.
- **MongoDB transactions** go through the `MongoTransactionManager` declared in `MongoConfiguration`; indexes are
  created explicitly by the module owning the collection, never automatically
  ([ADR 0022](../docs/adr/0022-inventory-data-model.md)).
- **Dependency overrides** in `pom.xml` are commented with their reason and re-checked on every upgrade.
