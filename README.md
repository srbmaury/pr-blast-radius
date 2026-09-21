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
- Persistent repository → runtime service catalog
- Automatic service resolution for GitHub PR analysis
- Combined DB + runtime impact analysis
- Concise Markdown blast-radius report
- Explicit API to publish the report as a GitHub PR comment
- Evidence coverage states for PostgreSQL and runtime-service telemetry
- Explicit warnings when missing telemetry prevents a trustworthy safety conclusion

## Repository → service catalog

Register a repository once:

```http
PUT /api/v1/catalog/repositories/acme/orders
Content-Type: application/json

{
  "service": "orders-service"
}
```

Then this:

```text
GET /api/v1/pr/acme/orders/42/impact
```

automatically uses `orders-service`.

An explicit query parameter still overrides the catalog:

```text
GET /api/v1/pr/acme/orders/42/impact?service=orders-canary
```

Catalog endpoints:

```text
GET    /api/v1/catalog/repositories
GET    /api/v1/catalog/repositories/{owner}/{repo}
PUT    /api/v1/catalog/repositories/{owner}/{repo}
DELETE /api/v1/catalog/repositories/{owner}/{repo}
```

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
        +--> repository_service_mapping
        +--> retention cleanup
```

Runtime graph and catalog metadata are never written into the customer's PostgreSQL database.

## PR analysis API

```text
GET /api/v1/pr/{owner}/{repo}/{number}/changes
GET /api/v1/pr/{owner}/{repo}/{number}/impact
POST /api/v1/pr/{owner}/{repo}/{number}/comment
```

Optional service override:

```text
?service=orders-canary
```

Raw unified diffs can also be analyzed:

```text
POST /api/v1/pr/diff/changes
POST /api/v1/pr/diff/impact?service=orders-service
Content-Type: text/plain
```

## Runtime telemetry API

```text
POST /api/v1/telemetry/spans
POST /api/v1/telemetry/otlp-json/v1/traces
GET  /api/v1/telemetry/downstream?service=checkout-service&maxDepth=3
GET  /api/v1/telemetry/dependencies
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

## Evidence coverage

Every impact response now reports whether each evidence source was actually usable:

```text
POSTGRES_RUNTIME
- AVAILABLE
- NO_DATA
- UNAVAILABLE
- NOT_APPLICABLE

SERVICE_RUNTIME
- AVAILABLE
- NO_DATA
- UNAVAILABLE
- NOT_CONFIGURED
```

This prevents a dangerous interpretation of an empty result. If telemetry is missing, the PR report explicitly says that the analysis is incomplete and must not be treated as proof that the change is safe.

## Current limitations

- OTLP/HTTP JSON is supported, but native protobuf OTLP is not.
- Repository/service mapping is explicit rather than inferred.
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
