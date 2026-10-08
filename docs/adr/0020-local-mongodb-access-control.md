# 0020 — MongoDB access control in local development

- **Status:** Accepted
- **Date:** 2026-10-08

## Context and problem statement

The local stack runs MongoDB as a single-node replica set with `mongot`
([ADR 0007](0007-local-runtime-with-docker-compose.md)). Enabling access control on a replica set requires a
keyfile for internal authentication, an administrative user created before the replica set is initiated, and
credentials for every client. It is the production-grade setup, but it multiplies the moving parts of the very
first infrastructure step, whose purpose is to prove that MongoDB Search works on Community.

## Considered options

1. Access control enabled from the start (keyfile, root user, application user, `mongot` user).
2. Access control disabled locally, with compensating controls, revisited later.

## Decision outcome

Option 2, for local development only:

- `mongod` runs without `security.authorization`.
- **Compensating controls:** the host port is bound to `127.0.0.1`, so MongoDB is reachable only from this machine
  and from the Compose network; all data is synthetic or public ([ADR 0002](0002-domain-and-mvp-scope.md)).
- `mongot` still authenticates with SCRAM through a dedicated `searchCoordinator` user, so the search
  configuration already matches a secured deployment.
- **Revisit** when the orchestrator connects to MongoDB with its own user, and in any case before a public demo:
  access control will then be enabled through a superseding ADR.

## Consequences

### Positive

- The first step validates the riskiest part (Search on Community) with the fewest moving parts.

### Negative

- Any process on the developer machine can read and write the database.
- The application's connection settings will change when access control is enabled.
