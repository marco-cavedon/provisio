# Orchestrator

Spring Boot application ([ADR 0010](../docs/adr/0010-single-spring-boot-application.md)), built with Maven Wrapper
([ADR 0013](../docs/adr/0013-maven-wrapper.md)) and organised as a **Spring Modulith** modular monolith with
domain-oriented modules ([ADR 0016](../docs/adr/0016-spring-modulith.md)).

It implements the agent's tools as Camunda job workers and exposes the BFF API — the only backend the frontend
talks to ([ADR 0011](../docs/adr/0011-frontend-only-talks-to-bff.md)). It is the only writer to MongoDB
([ADR 0006](../docs/adr/0006-precomputed-forecast-with-on-demand-recompute.md)).
