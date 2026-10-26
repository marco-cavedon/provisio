# 0023 — MongoDB access control enabled, least-privilege users

- **Status:** Accepted — supersedes [ADR 0020](0020-local-mongodb-access-control.md)
- **Date:** 2026-10-26

## Context and problem statement

[ADR 0020](0020-local-mongodb-access-control.md) ran `mongod` without access control in local development and set two
triggers to revisit the decision: the orchestrator connecting to MongoDB with its own user, and any public demo.
Both are now imminent: the next step of v0.1.0 connects the orchestrator, and the repository is about to become
public. A public repository whose database accepts any client is the first thing a technical reviewer would notice.

## Decision drivers

- Least privilege for every client, starting with the first application connection.
- `docker compose up` must stay a single command, and restarting the stack must stay safe.
- No new secret that has to be stored, rotated or protected beyond the existing `.env`.

## Considered options

1. Keep access control disabled until a later milestone.
2. Enable access control now, bootstrapping the first user through the image's `MONGO_INITDB_ROOT_*` variables.
3. Enable access control now, bootstrapping the first user through MongoDB's localhost exception.

## Decision outcome

Option 3. Option 1 would change the application's connection settings twice. Option 2 was rejected because it was
not confirmed that the `mongodb-community-server` image applies those variables together with a replica set and a
custom configuration file; the localhost exception is a documented server feature.

- **Three users, least privilege:**

  | User | Role | Used by |
  | --- | --- | --- |
  | `admin` | `root` | `mongodb-init` only |
  | `mongot` | `searchCoordinator` | `mongot`, to sync search indexes |
  | `provisio` | `readWrite` on the `provisio` database | the orchestrator |

  `readWrite` is granted on the whole `provisio` database rather than on single collections: a collection-scoped grant
  is reported not to cover `createSearchIndexes`, which the orchestrator needs for vector search.
- **First user through the localhost exception.** With access control enabled and no user defined, `mongod` allows the
  first user to be created over the loopback interface only. `mongodb-init` runs in `mongod`'s network namespace
  (`network_mode: service:mongodb`) and connects to `localhost`. Once `admin` exists the exception is closed and later
  runs authenticate as `admin`, then create or update the service users so that password changes in `.env` apply on
  the next start.
- **Keyfile generated at every start.** A replica set with access control requires a keyfile for internal
  authentication. The replica set has a single member, so no other process reads it: the container entrypoint writes a
  random keyfile to `/tmp` with `0400` permissions, removing the previous one first, before starting `mongod`.
- **Passwords in `.env`:** `MONGODB_ADMIN_PASSWORD` and `MONGODB_APP_PASSWORD`, local values not linked to any external
  account. Compose stops if either is missing.

### Verification

Verified on a local stack on 2026-10-26, from a fresh volume
([pull request #19](https://github.com/marco-cavedon/provisio/pull/19)):

- On a fresh volume `mongodb-init` creates `admin` through the localhost exception from the shared network namespace;
  on later runs it authenticates as `admin` and updates the service users.
- The `mongod` healthcheck (`ping`) works without credentials.
- `mongot` keeps syncing: `$search` and `$vectorSearch` succeed when run as `provisio`, which also proves that
  `provisio` can create search indexes.
- Unauthenticated writes fail (`requires authentication`); `provisio` cannot write to another database (`not authorized`).
- A restart of `mongod` initially failed: the keyfile left by the previous start, with `0400` permissions, could not be
  overwritten by its owner. The entrypoint now removes it before generating a new one.

## Consequences

### Positive

- Every client authenticates with the least privilege it needs; the local setup matches a secured deployment.
- No keyfile to store or leak, and no extra secret beyond two local passwords.

### Negative

- Existing local volumes created without access control must be recreated once (`docker compose down -v`): their
  users already exist, so the localhost exception is closed and `admin` cannot be created.
- The admin password is not rotated automatically: changing `MONGODB_ADMIN_PASSWORD` after the first start breaks
  `mongodb-init` until the volume is recreated or the password is changed manually.
- The per-start keyfile is valid only for a single-member replica set; a multi-member deployment needs a shared keyfile
  managed as a secret.
- TLS is not enabled: traffic stays inside the Compose network and the host port is bound to `127.0.0.1`.
