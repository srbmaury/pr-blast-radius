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

## Implemented

- GitHub PR diff ingestion
- Java type change detection
- PostgreSQL migration detection for table / add / drop / rename column changes
- Runtime PostgreSQL query evidence through `pg_stat_statements`
- Evidence-based `CONFIRMED` findings
- Unit tests for parser precision and runtime DB evidence

## API

Analyze only the structural changes in a PR:

```text
GET /api/v1/pr/{owner}/{repo}/{number}/changes
```

Analyze changes plus PostgreSQL runtime evidence:

```text
GET /api/v1/pr/{owner}/{repo}/{number}/impact
```

You can also POST a raw unified diff to:

```text
POST /api/v1/pr/diff/changes
POST /api/v1/pr/diff/impact
Content-Type: text/plain
```

## Configuration

```bash
export GITHUB_TOKEN=...
export DATABASE_URL=jdbc:postgresql://localhost:5432/blast_radius
export DATABASE_USER=blast_radius
export DATABASE_PASSWORD=blast_radius
```

For runtime SQL evidence, the connected PostgreSQL instance must expose `pg_stat_statements`. If it is unavailable, the service returns no runtime DB evidence instead of inferring usage.

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

See [docs/architecture.md](docs/architecture.md) for the MVP architecture.

## Scope discipline

Not in the first version: Kafka, Kubernetes, Grafana/Datadog, AI-generated fixes, historical incidents, automatic PR generation, or support for every language/database.
