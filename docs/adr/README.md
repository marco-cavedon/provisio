# Architecture Decision Records

Decisions are recorded using [MADR](https://adr.github.io/madr/).
ADRs are immutable once accepted: a changed decision gets a **new** ADR that supersedes the old one.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-domain-and-mvp-scope.md) | Domain and MVP scope | Accepted |
| [0003](0003-separate-forecasting-retrieval-and-agent.md) | Separate forecasting, retrieval and agent reasoning | Accepted |
| [0004](0004-native-camunda-agentic-orchestration.md) | Native Camunda agentic orchestration | Accepted, validated by spike 001 |
| [0005](0005-python-stateless-forecasting-service.md) | Python stateless forecasting service | Accepted |
| [0006](0006-precomputed-forecast-with-on-demand-recompute.md) | Precomputed forecast with on-demand recompute | Accepted |
| [0007](0007-local-runtime-with-docker-compose.md) | Local runtime with Docker Compose | Accepted |
| [0008](0008-provider-agnostic-llm-on-free-tiers.md) | Provider-agnostic LLM and embeddings on free tiers | Superseded by [0017](0017-llm-provider-after-spike-001.md) |
| [0009](0009-monorepo.md) | Monorepo | Accepted |
| [0010](0010-single-spring-boot-application.md) | Single modular Spring Boot application | Accepted |
| [0011](0011-frontend-only-talks-to-bff.md) | Frontend talks only to the backend-for-frontend | Accepted |
| [0012](0012-react-for-the-frontend.md) | React for the frontend | Accepted |
| [0013](0013-maven-wrapper.md) | Maven with Maven Wrapper for the Java build | Accepted |
| [0014](0014-testing-strategy.md) | Testing strategy | Accepted |
| [0015](0015-ci-cd.md) | CI/CD with GitHub Actions | Accepted |
| [0016](0016-spring-modulith.md) | Spring Modulith and domain-oriented modules | Accepted |
| [0017](0017-llm-provider-after-spike-001.md) | LLM provider and configuration after spike 001 | Accepted |
| [0018](0018-trunk-based-development-and-tagged-releases.md) | Trunk-based development and tagged releases | Accepted |
| [0019](0019-job-workers-live-in-domain-modules.md) | Job workers live in their domain modules | Accepted |
| [0020](0020-local-mongodb-access-control.md) | MongoDB access control in local development | Superseded by [0023](0023-mongodb-access-control.md) |
| [0021](0021-agent-delegation-model.md) | Delegation model for AI-assisted development | Accepted |
| [0022](0022-inventory-data-model.md) | Inventory data model: movement ledger with stock snapshot | Accepted |
| [0023](0023-mongodb-access-control.md) | MongoDB access control enabled, least-privilege users | Accepted |

New ADRs start from [`template.md`](template.md).
