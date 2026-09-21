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
- Combined DB + runtime impact analysis
- Concise Markdown blast-radius report
- Explicit API to publish the report as a GitHub PR comment
- Unit tests for parser precision, runtime DB evidence, graph traversal, and report formatting

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

The current MVP accepts normalized outbound OpenTelemetry span observations:

```http
POST /api/v1/telemetry/spans
Content-Type: application/json
```

Example:

```json
[
  {
    "sourceService": "checkout-service",
    "targetService": "orders-service",
    "spanKind": "CLIENT",
    "observedAt": "2026-09-21T10:15:00Z"
  },
  {
    "sourceService": "orders-service",
    "targetService": "payment-service",
    "spanKind": "CLIENT",
    "observedAt": "2026-09-21T10:15:01Z"
  }
]
```

Inspect downstream dependencies:

```text
GET /api/v1/telemetry/downstream?service=checkout-service&maxDepth=3
GET /api/v1/telemetry/dependencies
```

Only outbound `CLIENT` and `PRODUCER` observations are counted, preventing the corresponding server span from double-counting the same call.

## Configuration

```bash
export GITHUB_TOKEN=...
export DATABASE_URL=jdbc:postgresql://localhost:5432/blast_radius
export DATABASE_USER=blast_radius
export DATABASE_PASSWORD=blast_radius
```

For runtime SQL evidence, PostgreSQL must expose `pg_stat_statements`. If it is unavailable, the system returns no DB runtime evidence instead of guessing.

## Current MVP limitations

- Telemetry ingestion accepts a normalized OpenTelemetry observation format; it is not yet a native OTLP HTTP/protobuf receiver.
- Runtime dependency edges are currently stored in memory and reset on restart.
- Repository-to-service ownership is explicit through the `service` parameter; no heuristic mapping is used.
- Static Java analysis currently detects changed types but does not yet build a full symbol-level call graph.
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
