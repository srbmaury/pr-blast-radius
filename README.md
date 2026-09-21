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
- Spring endpoint mapping change detection (`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`)
- Source-aware Java AST analysis that maps handler-body/signature changes to their owning Spring endpoint
- PostgreSQL table / add / drop / rename column detection
- Statement-scoped SQL parsing to reduce false positives
- PostgreSQL runtime query evidence through `pg_stat_statements`
- Runtime service graph with call counts, last-seen timestamps, depth limits, and cycle protection
- Bidirectional blast radius: callers into the changed service plus dependencies it calls
- Endpoint-aware caller filtering for changed Spring routes
- Trace-path causality that ties changed inbound endpoints to downstream calls actually observed beneath matching server spans
- OTLP/HTTP JSON trace adaptation using `service.name` + `peer.service`
- Stable endpoint identity from HTTP client `url.template` (with `http.route` compatibility fallback), RPC service/method, and messaging destination attributes
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
        +--> runtime_dependency_route_edge
        +--> repository_service_mapping
        +--> trace_span (short-lived lineage)
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
GET  /api/v1/telemetry/blast-radius?service=orders-service&maxDepth=3
GET  /api/v1/telemetry/blast-radius?service=orders-service&maxDepth=3&endpoint=HTTP%20POST%20/orders
GET  /api/v1/telemetry/trace-causality?service=orders-service&endpoint=HTTP%20POST%20/orders
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

Runtime topology retention defaults to 7 days; raw trace lineage defaults to 24 hours:

```bash
export METADATA_RETENTION_HOURS=168
export TRACE_RETENTION_HOURS=24
export TRACE_MAX_ROOT_SPANS=1000
export METADATA_CLEANUP_INTERVAL_MS=3600000
```

Trace lineage intentionally stores only identifiers and low-cardinality metadata required for causality: trace/span IDs, parent linkage, service names, span kind, normalized endpoint identity, target service, and timestamp. Request/response payloads and arbitrary span attribute bags are not persisted.

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

ENDPOINT_RUNTIME
- AVAILABLE
- NO_DATA
- UNAVAILABLE
- NOT_CONFIGURED
- NOT_APPLICABLE

TRACE_PATH
- AVAILABLE
- NO_DATA
- UNAVAILABLE
- NOT_CONFIGURED
- NOT_APPLICABLE
```

This prevents a dangerous interpretation of an empty result. If telemetry is missing, the PR report explicitly says that the analysis is incomplete and must not be treated as proof that the change is safe.

## Runtime blast-radius semantics

For a changed `orders-service`:

```text
frontend -> checkout -> orders -> payment -> ledger
                         ^
                     changed service
```

the runtime blast radius separates:

- **callers**: `checkout -> orders`, `frontend -> checkout`
- **dependencies**: `orders -> payment`, `payment -> ledger`

This is important because callers are often the systems most directly exposed to a changed service contract. Cycles are handled safely and duplicate physical edges are suppressed in impact findings.

## Endpoint-aware caller filtering

When a PR changes a Spring mapping such as:

```java
@PostMapping("/orders")
```

the change is normalized to:

```text
HTTP POST /orders
```

OpenTelemetry client spans use the same identity when they expose stable route metadata:

```text
http.request.method = POST
url.template = /orders
```

This lets the product distinguish:

```text
checkout -> orders [HTTP POST /orders]      impacted
admin    -> orders [HTTP GET /orders/{id}]  unrelated
```

Filtering is applied only to the **direct caller → changed service** edge. Once a matching caller is identified, its upstream callers are still retained in the blast-radius path.

If route metadata is missing, the product reports `ENDPOINT_RUNTIME = UNAVAILABLE` rather than pretending endpoint-level precision exists.

Endpoint filtering also activates for handler-body and method-signature changes. For GitHub PRs, the analyzer:

```text
unified diff
   ↓
old/new changed line numbers
   ↓
fetch base + head Java source
   ↓
JavaParser AST
   ↓
enclosing Spring handler method
   ↓
class mapping + method mapping
   ↓
HTTP METHOD /route
```

For example:

```java
@RequestMapping("/orders")
class OrderController {
    @PostMapping
    Order create(OrderRequest request) {
        return service.create(request); // changed line
    }
}
```

is resolved to:

```text
HTTP POST /orders
```

The analyzer checks both the PR base and head revisions, so replacement edits and deletion-only body changes still map to the endpoint.

## Trace-path causality

When a changed endpoint has retained OpenTelemetry server spans, downstream impact is reconstructed from actual span ancestry rather than from the service's entire dependency graph.

For example:

```text
checkout CLIENT POST /orders
        ↓
orders SERVER POST /orders   ← changed endpoint
        ↓
orders INTERNAL
        ↓
orders CLIENT POST /payments
        ↓
payment SERVER POST /payments
        ↓
payment CLIENT POST /ledger
```

produces causal downstream findings:

```text
POST /orders
  → payment-service POST /payments
  → ledger-service POST /ledger
```

An unrelated call such as `orders → email-service POST /notify` is excluded unless it appears beneath a matching `POST /orders` server span in the retained trace tree.

If matching endpoint traces are unavailable, the product falls back to the broader service dependency graph and marks downstream findings as `POSSIBLE` instead of `CONFIRMED`.

Trace-path evidence is observational: it proves that a path occurred in retained traces, not that every possible production path was sampled. Parent/child span ancestry is supported in this version; asynchronous producer→consumer causality represented only through span links is not reconstructed yet.

## Current limitations

- OTLP/HTTP JSON is supported, but native protobuf OTLP is not.
- Repository/service mapping is explicit rather than inferred.
- Source-aware endpoint ownership currently supports Java/Spring methods with literal mapping paths. Custom composed annotations, path constants, and dynamically constructed mappings are not resolved yet.
- Asynchronous messaging causality through OpenTelemetry span links is not reconstructed yet.
- Kafka, Kubernetes, Datadog/Grafana, historical incidents, and AI-generated fixes remain out of scope.

## Local development

Requirements:

- Java 21+
- Maven 3.9+
- PostgreSQL

Source-aware endpoint ownership uses JavaParser 3.28.2 with Java 21 parsing enabled.

```bash
mvn spring-boot:run
```

See [docs/architecture.md](docs/architecture.md) for the architecture.
