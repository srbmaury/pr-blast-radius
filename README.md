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
- Combined DB + runtime impact analysis
- Concise Markdown blast-radius report
- Explicit API to publish the report as a GitHub PR comment
- Unit tests for parser precision, runtime DB evidence, graph traversal, OTLP JSON adaptation, runtime impact, and report formatting

## PR analysis API

Structural changes only:

```text
GET /api/v1/pr/{owner}/{repo}/{number}/changes
```

Combine PR changes with production evidence. Pass the owning runtime service explicitly:

```text
GET /api/v1/pr/{owner}/{repo}/{number}/impact?service=orders-service
```

Publish the generated report back to the PR:

```text
POST /api/v1/pr/{owner}/{repo}/{number}/comment?service=orders-service
```

Raw unified diffs can also be analyzed:

```text
POST /api/v1/pr/diff/changes
POST /api/v1/pr/diff/impact?service=orders-service
Content-Type: text/plain
```

## Runtime telemetry API

Normalized observations are supported directly:

```text
POST /api/v1/telemetry/spans
```

OTLP/HTTP JSON-shaped trace payloads are also supported:

```text
POST /api/v1/telemetry/otlp-json/v1/traces
Content-Type: application/json
```

The adapter extracts:

```text
resource.attributes["service.name"]
        +
span.attributes["peer.service"]
        +
CLIENT / PRODUCER span kind
        ↓
source-service -> target-service
```

Only outbound `CLIENT` and `PRODUCER` spans create edges, preventing corresponding server spans from double-counting a call.

Inspect the resulting graph:

```text
GET /api/v1/telemetry/downstream?service=checkout-service&maxDepth=3
GET /api/v1/telemetry/dependencies
```

## Configuration

```bash
export GITHUB_TOKEN=...
export DATABASE_URL=jdbc:postgresql://localhost:5432/blast_radius
export DATABASE_USER=blast_radius
export DATABASE_PASSWORD=blast_radius
```

For runtime SQL evidence, PostgreSQL must expose `pg_stat_statements`. If it is unavailable, the system returns no DB runtime evidence instead of guessing.

## Current MVP limitations

- OTLP/HTTP JSON is supported, but native protobuf OTLP is not.
- Runtime dependency edges are stored in memory and reset on restart.
- Repository-to-service ownership is explicit through the `service` parameter; no heuristic mapping is used.
- Static Java analysis detects changed types but does not yet build a full symbol-level call graph.
- Kafka, Kubernetes, Datadog/Grafana, historical incidents, and AI-generated fixes are intentionally out of scope.

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

See [docs/architecture.md](docs/architecture.md) for the architecture.
