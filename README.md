# PR Blast Radius

Production-aware impact analysis for backend pull requests.

## Goal

Given a GitHub PR, answer one question:

> What can this change break in production?

The MVP intentionally supports only:

- GitHub pull requests
- Java / Spring Boot change detection
- PostgreSQL migrations and runtime SQL evidence
- OpenTelemetry-derived service dependencies

Findings are evidence-based:

- **CONFIRMED** — observed in runtime or database telemetry
- **STRONG** — direct static/schema evidence
- **POSSIBLE** — inferred relationship requiring review

## Implemented

- GitHub PR diff ingestion
- Java type change detection
- PostgreSQL table / add / drop / rename column detection
- Statement-scoped SQL parsing to reduce false positives
- PostgreSQL runtime query evidence through `pg_stat_statements`
- Runtime service graph with call counts, last-seen timestamps, depth limits, and cycle protection
- OTLP/HTTP JSON trace adaptation using `service.name` + `peer.service`
- Persistent runtime dependency edges in a dedicated metadata database
- TTL-based cleanup of stale runtime edges
- Combined DB + runtime impact analysis
- Concise Markdown blast-radius report
- Explicit API to publish the report as a GitHub PR comment

## Data separation

The application uses two independent databases:

```text
Customer PostgreSQL
        |
        +--> read pg_stat_statements
        +--> production evidence only

Product metadata database
        |
        +--> runtime_dependency_edge
        +--> call counts
        +--> last-seen timestamps
        +--> retention cleanup
```

Runtime graph metadata is never written into the customer's PostgreSQL database.

By default, product metadata uses a local H2 file:

```text
./data/pr-blast-radius-metadata
```

It can be replaced with a dedicated external database through configuration.

## PR analysis API

```text
GET /api/v1/pr/{owner}/{repo}/{number}/changes
GET /api/v1/pr/{owner}/{repo}/{number}/impact?service=orders-service
POST /api/v1/pr/{owner}/{repo}/{number}/comment?service=orders-service
```

Raw unified diffs can also be analyzed:

```text
POST /api/v1/pr/diff/changes
POST /api/v1/pr/diff/impact?service=orders-service
Content-Type: text/plain
```

## Runtime telemetry API

Normalized observations:

```text
POST /api/v1/telemetry/spans
```

OTLP/HTTP JSON-shaped traces:

```text
POST /api/v1/telemetry/otlp-json/v1/traces
Content-Type: application/json
```

Inspect the persisted graph:

```text
GET /api/v1/telemetry/downstream?service=checkout-service&maxDepth=3
GET /api/v1/telemetry/dependencies
```

## Configuration

Customer evidence database:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/customer_db
export DATABASE_USER=...
export DATABASE_PASSWORD=...
```

Product metadata database:

```bash
export METADATA_DATABASE_URL='jdbc:h2:file:./data/pr-blast-radius-metadata;AUTO_SERVER=TRUE'
export METADATA_DATABASE_USER=sa
export METADATA_DATABASE_PASSWORD=
export METADATA_DATABASE_DRIVER=org.h2.Driver
```

Retention defaults to 7 days:

```bash
export METADATA_RETENTION_HOURS=168
export METADATA_CLEANUP_INTERVAL_MS=3600000
```

GitHub write access:

```bash
export GITHUB_TOKEN=...
```

For runtime SQL evidence, customer PostgreSQL must expose `pg_stat_statements`. If it is unavailable, the system returns no DB runtime evidence instead of guessing.

## Current limitations

- OTLP/HTTP JSON is supported, but native protobuf OTLP is not.
- Repository-to-service ownership is explicit through the `service` parameter.
- Static Java analysis detects changed types but does not yet build a full symbol-level call graph.
- Kafka, Kubernetes, Datadog/Grafana, historical incidents, and AI-generated fixes remain out of scope.

## Local development

Requirements:

- Java 21+
- Maven 3.9+
- PostgreSQL

```bash
mvn spring-boot:run
```

See [docs/architecture.md](docs/architecture.md) for the architecture.
