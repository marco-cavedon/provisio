# Infrastructure

Local runtime for Provisio ([ADR 0007](../docs/adr/0007-local-runtime-with-docker-compose.md)), started from the
repository root with `docker compose up -d`.

| Service | Image | Role | Host port |
| --- | --- | --- | --- |
| `orchestration` | `camunda/camunda:8.9.23` | Zeebe, Operate, Tasklist; H2 secondary storage | 8080, 26500, 9600 |
| `connectors` | `camunda/connectors-bundle:8.9.14` | Connectors runtime, including the AI Agent connector | — |
| `mongodb` | `mongodb/mongodb-community-server:9.0.2-ubi8` | Single-node replica set `rs0` | 27017 |
| `mongot` | `mongodb/mongodb-community-search:1.70.5` | MongoDB Search and Vector Search | — |
| `camunda-data-init`, `mongodb-init` | | One-shot initialisation jobs | — |

Every host port is bound to `127.0.0.1`. Versions can be overridden with `CAMUNDA_VERSION`,
`CAMUNDA_CONNECTORS_VERSION`, `MONGODB_VERSION`, `MONGOT_VERSION`.

## Configuration

| File | Purpose |
| --- | --- |
| `camunda/application.yaml` | Orchestration cluster, from the official lightweight distribution (basic auth `demo` / `demo`) |
| `mongodb/mongod.conf` | Replica set `rs0`, small WiredTiger cache, connection to `mongot` |
| `mongodb/mongot.yml` | `mongot` sync source, gRPC, health check and metrics endpoints |
| `mongodb/init/init-replica-set.js` | Initiates the replica set and creates the `mongot` user (`searchCoordinator` role); idempotent |
| `mongodb/scripts/verify-search.js` | End-to-end check of `$search` and `$vectorSearch` |

Required `.env` variables: `LLM_API_KEY`, `MONGOT_PASSWORD`. Compose stops immediately with a clear message if
one is missing.

## Design notes

- **Replica set, not standalone.** `mongot` keeps its indexes in sync through change streams, which require a
  replica set. MongoDB Search for self-managed deployments requires MongoDB 8.3 or later; the stack uses 9.0.
- **Initialisation as a one-shot service.** `mongodb-init` runs after `mongod` is healthy and before `mongot` starts;
  it is idempotent, so restarting the stack is always safe.
- **`mongot` password as a Compose secret**, mounted as a file (`/run/secrets/mongot_password`), never in the image or
  in the repository.
- **Access control is disabled on `mongod` in local development**
  ([ADR 0020](../docs/adr/0020-local-mongodb-access-control.md)).

## Verify

```bash
docker compose ps                       # all long-running services "healthy"; init jobs "exited (0)"
docker compose logs mongodb-init        # "Replica set initiated" and "User mongot created"
docker compose exec mongodb mongosh --quiet --file /scripts/verify-search.js
```

The last command creates a throwaway collection with a full-text and a vector index, waits for both to become
queryable and runs `$search` and `$vectorSearch`. Expected output:

```text
OK: $search and $vectorSearch both returned the expected document.
```

Camunda: Operate at <http://localhost:8080/operate> (`demo` / `demo`).

From the host, connect to MongoDB with `directConnection=true`: the replica set member is registered as
`mongodb:27017`, a name that only resolves inside the Compose network.

```bash
mongosh "mongodb://localhost:27017/?directConnection=true"
```

## Resource usage

Measure with `docker stats --no-stream` once everything is healthy, and record the result in this README.

## Troubleshooting

- **`mongot` unhealthy** → `docker compose logs mongot`. Authentication errors mean the password in `.env` changed
  after the user was created: re-run `docker compose up -d mongodb-init`, which updates it.
- **`mongot` cannot write `/data/mongot`** → volume ownership issue; report it, it means the image runs as a
  non-root user that does not own the named volume.
- **Search index never becomes queryable** → check that `mongot` is healthy and that `mongod` logs show no errors
  connecting to `mongot:27028`.
- **Clean restart** → `docker compose down -v` removes every volume (all process data and all MongoDB data).
