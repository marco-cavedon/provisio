# 0007 — Local runtime with Docker Compose

- **Status:** Accepted
- **Date:** 2026-10-06

## Context and problem statement

Whoever clones the repository must see a working process within minutes, without SaaS accounts
that expire and without pages of setup instructions.

## Decision outcome

- **Docker Compose**, one command: `docker compose up`.
- **Camunda 8 Self-Managed**, based on the official *lightweight* Compose distribution
  (latest stable minor at bootstrap; 8.9 at the time of writing). No Keycloak, Web Modeler or Optimize;
  models are authored with Camunda Desktop Modeler.
- **MongoDB Community Edition** (not Atlas) with **`mongot`** for Search / Vector Search.
  `mongot` synchronises indexes through change streams, so MongoDB runs as a **single-node replica set**,
  not as a standalone `mongod`.
- "Up and running" means a **ready demo**, not empty infrastructure:
  - healthchecks and `depends_on: condition: service_healthy` for ordered startup;
  - BPMN/DMN deployed automatically at application startup;
  - a seed job loading data, supplier documents, embeddings and the vector index;
  - the only manual step is copying `.env.example` to `.env` and adding API keys.

## Consequences

### Positive

- Reproducible demo, independent of SaaS trials; mirrors a real self-managed deployment.

### Negative

- Heavy local footprint (notably the search/secondary storage of Camunda). Minimum RAM requirements
  will be measured and stated in the README.
- Community Search / Vector Search reached GA recently; rough edges are possible.
