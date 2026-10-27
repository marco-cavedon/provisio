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

- `ProvisioApplicationTests` — the application context assembles (no broker needed: job workers are disabled;
  MongoDB from a container, because the inventory module creates its indexes at startup).
- `ModularityTests` — module boundaries are respected; module documentation is written to `target/spring-modulith-docs`.
- `MongoConfigurationTests` — the application refuses to start without a MongoDB password (no Docker needed).
- `MongoTransactionTests` — multi-document transactions commit and, above all, roll back against a real MongoDB
  (Docker required). Without a transaction manager, `@Transactional` would be silently ignored.
- Inventory module ([ADR 0022](../docs/adr/0022-inventory-data-model.md)):
  - `RecordMovementTests`, `TransactionRetryTests` — command validation and retry classification (no Docker needed).
  - `InventoryServiceTests` — the write path: rejections leave no trace, idempotency, key conflicts, concurrent
    issues never making stock negative.
  - `InventoryReconciliationTests` — after a seeded random sequence of movements, every SKU's `onHand` equals the
    sum of its deltas.
  - `InventoryIndexesTests` — collections and indexes exist, creation is idempotent, a conflicting index stops startup.
  - `GetStockLevelWorkerTests` — the tool contract of `provisio.inventory.get-stock-level`, case by case (no Docker
    needed).
  - `GetStockLevelWorkerProcessTests` — the worker through the real invocation path: Camunda Process Test runs a
    one-task test process (`src/test/resources/bpmn`) on a Camunda engine in Docker, against MongoDB in Docker.

Integration tests share one Spring context and one MongoDB container: keep their `@SpringBootTest` properties and
imports identical, or each variant starts its own.
Process tests use a shared Camunda runtime (`camunda.process-test.runtime-mode=shared`) pinned to the stack's engine
version; the first run downloads the `camunda/camunda` image.

## Inventory module

Products and stock levels for a single warehouse: an append-only movement ledger plus a per-SKU snapshot, written
in one transaction ([ADR 0022](../docs/adr/0022-inventory-data-model.md)). Other modules use `InventoryQueries` and
`InventoryOperations`; everything else is internal.

- **Idempotency key:** derive it from the business event (e.g. `purchase-order:PO-42:line:1`), never from a random
  value. The same key with a different SKU, type, quantity or `occurredAt` throws `IdempotencyKeyConflictException`.
- **Rejections leave no trace:** a movement rejected for insufficient stock is not recorded, so a later attempt with
  the same key is evaluated again.
- **Retries:** transient transaction errors are retried up to three times; safe because movements are idempotent.
- **Indexes** are created at startup by `InventoryIndexes`. A conflicting existing index stops startup instead of
  being ignored.
- **Known limit — back-dated movements:** the non-negative check uses the current stock, not the stock at
  `occurredAt`. A back-dated issue can make a reconstruction of a past day show negative stock even though current
  stock is not negative. Acceptable for the MVP; relevant when replaying a period (roadmap R8).

## Run

### In Docker Compose

`docker compose up -d` in the repository root starts the orchestrator with the rest of the stack, from the image
published for the version in `docker-compose.yml`; if that image cannot be pulled, Compose builds it from the
`Dockerfile`. After a code change, rebuild it with `docker compose up -d --build orchestrator`; follow it with
`docker compose logs -f orchestrator`.

A local build gets the same name as the published image and takes its place: `docker compose up -d` keeps running
it until you run `docker compose pull orchestrator` to go back to the published one.

The image is built in three stages: the jar with the Maven Wrapper (tests skipped: they need Docker and run in CI),
then Spring Boot's layer extraction, then a JRE image running as a non-root user. The release workflow publishes it
for `linux/amd64` and `linux/arm64`; the jar is built once, natively, and only the last stage is built per
platform. Inside the Compose network the
orchestrator connects to the replica set `rs0` through the service name `mongodb`. It has no host port and no
healthcheck yet: both come with the BFF.

### On the host, for development

Stop the container first, or two orchestrators compete for the same jobs: `docker compose stop orchestrator`.
`run-local.sh` refuses to start while the container is running; the IDE does not check, so stop it by hand there too.

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
parsing the file rather than sourcing it, checks that `mongodb` and `orchestration` are healthy and that the
orchestrator container is not running, then runs
`./mvnw spring-boot:run`. When `application.yaml` starts reading a new environment variable, add it to `REQUIRED_VARS`.

Without the script, pass the variable explicitly from the `orchestrator` directory:

```bash
MONGODB_APP_PASSWORD=$(grep '^MONGODB_APP_PASSWORD=' ../.env | cut -d= -f2-) ./mvnw spring-boot:run
```

In IntelliJ IDEA, set `MONGODB_APP_PASSWORD` in the run configuration's environment variables, or point the
EnvFile plugin to `.env`. Startup fails with "MongoDB password is not set" if the variable is missing: Spring's
configuration binding would otherwise pass the unresolved placeholder through as the password, and the error would
appear only at the first query, as an authentication failure (`MongoConfiguration`).

## Stop

```bash
orchestrator/stop-local.sh
```

The script stops everything in order: first the orchestrator on the host, whether started by `run-local.sh` or by
the IDE, with SIGTERM so Spring shuts down gracefully (up to 30 seconds); then the stack with `docker compose down`,
including the orchestrator container.
Containers and network are removed, **volumes are kept**, so Camunda and MongoDB data survive. If the orchestrator
does not stop in time, the script leaves the stack running and exits with an error.

The orchestrator is found by its command line: a `java` process whose main class is `ProvisioApplication`. Processes
that only mention the class name, such as `grep ProvisioApplication`, are not touched. To delete the data as well,
run `docker compose down -v` from the repository root, deliberately (see [infra/README.md](../infra/README.md)).

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
