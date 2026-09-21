# PR Blast Radius

Production-aware impact analysis for backend pull requests.

## MVP

Given a GitHub PR, answer one question:

> What can this change break in production?

The first version intentionally supports only:

- GitHub pull requests
- Java / Spring Boot services
- PostgreSQL
- OpenTelemetry service dependencies

Findings are evidence-based, not opaque risk scores:

- **CONFIRMED** — observed in runtime or database telemetry
- **STRONG** — direct static/code/schema dependency
- **POSSIBLE** — inferred relationship that needs review

## Target output

```text
PR #421 — Change Impact

Changed:
orders.status

Direct code references:
- OrderService
- RefundService

Runtime dependencies:
- checkout -> orders-service
- refund-worker -> orders-service

Database usage:
- orders.status read 18,240 times / 24h
- last production read: 3 min ago

Confidence: CONFIRMED
```

## MVP architecture

See [docs/architecture.md](docs/architecture.md).

## Local development

Requirements:

- Java 21+
- Maven 3.9+
- PostgreSQL

```bash
mvn spring-boot:run
```

Health endpoint:

```text
GET /actuator/health
```

## Scope discipline

Not in the first version: Kafka, Kubernetes, Grafana/Datadog, AI-generated fixes, historical incidents, automatic PR generation, or support for every language/database.
