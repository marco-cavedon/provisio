# Infrastructure

Local runtime for Provisio ([ADR 0007](../docs/adr/0007-local-runtime-with-docker-compose.md)), started from the
repository root with `docker compose up -d`.

| Service | Image | Role | Host port |
| --- | --- | --- | --- |
| `orchestration` | `camunda/camunda:8.9.23` | Zeebe, Operate, Tasklist; H2 secondary storage | 8080, 26500, 9600 |
| `connectors` | `camunda/connectors-bundle:8.9.14` | Connectors runtime, including the AI Agent connector | — |
| `mongodb` | `mongodb/mongodb-community-server:9.0.2-ubi8` | Single-node replica set `rs0` | 27017 |
| `mongot` | `mongodb/mongodb-community-search:1.70.5` | MongoDB Search and Vector Search | — |
| `orchestrator` | `ghcr.io/marco-cavedon/provisio-orchestrator:0.1.1`, or built from `../orchestrator` | Provisio job workers (agent tools); see [its README](../orchestrator/README.md) | — |
| `camunda-data-init`, `mongodb-init` | | One-shot initialisation jobs | — |

Every host port is bound to `127.0.0.1`. Versions can be overridden with `CAMUNDA_VERSION`,
`CAMUNDA_CONNECTORS_VERSION`, `MONGODB_VERSION`, `MONGOT_VERSION`, `PROVISIO_VERSION`.

The orchestrator image is published for `linux/amd64` and `linux/arm64` on every version tag
([ADR 0024](../docs/adr/0024-image-publication-when-public.md)). When it cannot be pulled, for example before the first
publication, Compose builds it from source, which takes a few minutes the first time.

## Configuration

| File | Purpose |
| --- | --- |
| `camunda/application.yaml` | Orchestration cluster, from the official lightweight distribution (basic auth `demo` / `demo`) |
| `mongodb/mongod.conf` | Replica set `rs0`, small WiredTiger cache, connection to `mongot` |
| `mongodb/mongot.yml` | `mongot` sync source, gRPC, health check and metrics endpoints |
| `mongodb/init/init-replica-set.js` | Initiates the replica set and creates the `admin`, `mongot` and `provisio` users; idempotent |
| `mongodb/scripts/verify-search.js` | End-to-end check of `$search` and `$vectorSearch`, run as the application user |

Required `.env` variables: `MONGOT_PASSWORD`, `MONGODB_ADMIN_PASSWORD`, `MONGODB_APP_PASSWORD`. Compose stops
immediately with a clear message if one is missing. `LLM_API_KEY` is optional until the agent process exists.

`init-env.sh` creates `.env` from `.env.example` and fills in the three passwords with random values, only where they
are empty: it never overwrites a value, prints only the names of what it generated, and leaves the file readable by
its owner only. It refuses to generate `MONGODB_ADMIN_PASSWORD` when the MongoDB data volume already exists, because
the admin user keeps the password it was created with; the other two users are updated at every start. It needs
bash (on Windows, WSL or Git Bash); without it, copy `.env.example` and set the values by hand
(`openssl rand -hex 16`).

## Design notes

- **Replica set, not standalone.** `mongot` keeps its indexes in sync through change streams, which require a
  replica set. MongoDB Search for self-managed deployments requires MongoDB 8.3 or later; the stack uses 9.0.
- **Initialisation as a one-shot service.** `mongodb-init` runs after `mongod` is healthy and before `mongot` starts;
  it is idempotent, so restarting the stack is always safe.
- **`mongot` password as a Compose secret**, never in the image or in the repository. `mongot` refuses a password
  file readable by anyone but its owner, while Compose mounts secrets world-readable: the `mongot` entrypoint copies
  the secret to `/tmp/mongot_password` with `0400` permissions, then starts the image's original command.
- **Official docs lag behind the image**: `syncSource.replicaSet.tls` and `.replicationReader` from the
  installation guide are not recognised by `mongot` 1.70.5 and are omitted.
- **Access control is enabled on `mongod`.** Three users, each with the least privilege it needs:

  | User | Role | Used by |
  | --- | --- | --- |
  | `admin` | `root` | `mongodb-init` only |
  | `mongot` | `searchCoordinator` | `mongot`, to sync search indexes |
  | `provisio` | `readWrite` on the `provisio` database | the orchestrator |

  `readWrite` is granted on the whole database rather than on single collections: a collection-scoped grant is
  reported not to cover `createSearchIndexes`.
- **First user through the localhost exception.** With access control enabled and no user yet, `mongod` accepts the
  creation of the first user only over the loopback interface. `mongodb-init` therefore runs in `mongod`'s network
  namespace (`network_mode: service:mongodb`) and connects to `localhost`. Once `admin` exists the exception is
  closed, and later runs authenticate as `admin`.
- **Keyfile generated at every start.** A replica set with access control requires a keyfile for internal
  authentication. With a single member nothing else reads it, so the entrypoint writes a random one to `/tmp` with
  `0400` permissions before starting `mongod`. Not valid for a multi-member replica set, where all members must
  share the same keyfile.

## Verify

```bash
docker compose ps                       # all long-running services "healthy"; init jobs "exited (0)"
docker compose logs mongodb-init        # first run: "Replica set initiated", then "User admin|mongot|provisio created"
docker compose exec mongodb mongosh -u provisio -p --authenticationDatabase admin --quiet \
  --file /scripts/verify-search.js      # prompts for MONGODB_APP_PASSWORD
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
mongosh "mongodb://localhost:27017/provisio?directConnection=true&authSource=admin" -u provisio -p
```

## Resource usage

Measured with `docker stats --no-stream` on 2026-10-08, stack idle and healthy, after one run of `verify-search.js`:

| Service | Memory | Limit |
| --- | --- | --- |
| `orchestration` | ~800 MiB | 1 GiB |
| `mongot` | ~480 MiB | none |
| `connectors` | ~340 MiB | 512 MiB |
| `mongodb` | ~130 MiB | 1 GiB |
| **Total** | **~1.75 GiB** | |

Plan for at least **4 GiB of RAM available to Docker**: usage grows with data, indexes and process instances, and the
orchestrator (limit 768 MiB, not yet measured) and the forecasting service add to it. `mongot` has no memory limit yet: it is a JVM, and a
limit chosen without load data risks out-of-memory kills; it will be set once real indexes exist.

## Known issues

- **MongoDB on Linux kernel 6.19 and newer** ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)): mongod
  8.0+ refuses to start, with "Linux kernel versions 6.19 and newer has a known incompatibility", because the per-CPU
  caches of its TCMalloc allocator rely on `rseq`. This affects hosts such as Ubuntu 26.04. The `mongodb` service sets
  `GLIBC_TUNABLES=glibc.pthread.rseq=1`, so glibc keeps `rseq` and TCMalloc uses per-thread caches instead: slightly
  slower allocation, irrelevant for a local demo. It is a workaround, not a fix, and it is removed once a MongoDB
  release solves the issue. The integration tests apply the same setting, and CI runs on Ubuntu 26.04 to cover it.

## Troubleshooting

- **`mongot` unhealthy** → `docker compose logs mongot`. Authentication errors mean the password in `.env` changed
  after the user was created: re-run `docker compose up -d mongodb-init`, which updates it.
- **`mongot` restarts with "permissions are too permissive"** → the password file reached `mongot` without `0400`
  permissions; check that the service still uses the entrypoint defined in `docker-compose.yml`.
- **`mongot` running but "unhealthy"** → `docker compose exec mongot curl -s http://localhost:8080/health` must
  return `{"status":"SERVING"}`; the installation guide documents a plain-text response instead.
- **Search index never becomes queryable** → check that `mongot` is healthy and that `mongod` logs show no errors
  connecting to `mongot:27028`.
- **`mongodb-init` fails with "Cannot authenticate as admin"** → either `MONGODB_ADMIN_PASSWORD` changed after the
  first start (the admin password is not rotated automatically), or the volume was created before access control
  was enabled: users exist but no `admin`, and the localhost exception is closed. For local data, recreate the
  volume with `docker compose down -v`.
- **Upgrading a stack created before access control** → run `docker compose down -v` once, add the two new
  variables to `.env`, then `docker compose up -d`. MongoDB data is lost; it is test data only.
- **Clean restart** → `docker compose down -v` removes every volume (all process data and all MongoDB data).
- **`required variable … is missing a value` on `down` or `stop`** → Compose checks the required variables for every
  command, not only `up`. With the right `.env` elsewhere, point to it: `docker compose --env-file <file> down`. If it
  is lost, placeholders are enough to stop and remove: `MONGOT_PASSWORD=- MONGODB_ADMIN_PASSWORD=-
  MONGODB_APP_PASSWORD=- docker compose down -v` (shell variables take precedence over `.env`).
