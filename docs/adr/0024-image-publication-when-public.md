# 0024 — Docker images are published once the repository is public

- **Status:** Accepted — amends the delivery part of [ADR 0015](0015-ci-cd.md) and [ADR 0018](0018-trunk-based-development-and-tagged-releases.md)
- **Date:** 2026-10-27

## Context and problem statement

ADR 0015 and ADR 0018 state that every version tag publishes the services' Docker images to GitHub Container
Registry, and that the demo Compose file pulls them instead of building locally. Milestone v0.1.0 is the first tag,
and the orchestrator is the first service with an image. The repository is still private: a package published from it
is private too, so pulling it would require a GitHub login, which defeats the purpose of a one-command demo
([ADR 0007](0007-local-runtime-with-docker-compose.md)), and nobody outside the repository can see it yet.

## Decision drivers

- A clean clone must start with `docker compose up -d` and no registry credentials.
- Release tags must not contradict an accepted decision.
- No delivery machinery without an audience: the same incremental rule ADR 0015 applies to pipelines.

## Considered options

1. Publish private images now and require `docker login ghcr.io` to run the demo.
2. Publish images only once the repository is public; until then Compose builds them locally.

## Decision outcome

Option 2.

- Until the repository is public, Compose builds the orchestrator image from `orchestrator/Dockerfile`; CI builds
  it on every pull request, so the Dockerfile cannot rot.
- Image publication on version tags, and switching Compose from `build` to the published images, is an item of the
  go-public checklist in the [roadmap](../roadmap.md#before-the-repository-goes-public). Tags created before then
  (v0.1.0 onwards) publish no images.
- Everything else in ADR 0015 and ADR 0018 still holds.

## Consequences

### Positive

- The demo runs from a clean clone without credentials, and every tag is consistent with the recorded decisions.

### Negative

- The first `docker compose up` builds the orchestrator, which takes a few minutes and needs network access to
  Maven Central.
- Images of releases tagged before going public are never published; those versions can only be built from source.
