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
- Static outbound API extraction from changed Java methods and same-class helper calls
- Supported static clients: RestTemplate, RestClient, WebClient, and resolvable Spring Cloud OpenFeign interfaces
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
- Tenant-scoped runtime edges, trace lineage, and repository/service mappings
- Separate hashed tenant API and telemetry-ingestion credentials for hosted mode
- Verified GitHub App installation binding through one-time state + user OAuth
- Installation-scoped GitHub tokens for hosted PR reads/writes
- Signed, idempotent GitHub webhooks for automatic PR analysis
- Updatable bot-owned PR report comment plus neutral GitHub Check
- Same-origin customer dashboard for GitHub onboarding, repository mapping, telemetry status, and manual PR analysis
- Automatic service resolution for GitHub PR analysis
- Combined DB + runtime impact analysis
- Concise Markdown blast-radius report
- Explicit API to publish the report as a GitHub PR comment
- Evidence coverage states for PostgreSQL and runtime-service telemetry
- Explicit warnings when missing telemetry prevents a trustworthy safety conclusion

## Customer dashboard

The Spring Boot app serves the first customer-facing workspace at:

```text
/
```

The dashboard uses the existing authenticated APIs rather than a separate frontend backend. It supports the complete first-run workflow:

```text
tenant/API credentials
        ↓
connect verified GitHub App installation
        ↓
map repository → runtime service
        ↓
copy OTLP/HTTP JSON ingestion command
        ↓
observe runtime service edges
        ↓
analyze a PR and inspect evidence coverage
```

Hosted customers enter the tenant API token and optional ingestion token into the browser session. The UI stores these values in `sessionStorage`, not persistent `localStorage`, and provides a “Forget credentials” action.

The main dashboard has a same-origin Content Security Policy and renders API-provided repository, service, finding, and coverage values with text nodes rather than HTML injection.

GitHub installation opens in a popup when available. The OAuth callback notifies the dashboard and closes itself. The dashboard also polls the authenticated installation/candidate APIs during onboarding so the flow still completes if the browser severs the popup opener relationship.

If GitHub verifies multiple accessible App installations, the dashboard renders those verified candidates and lets the tenant explicitly claim one. The raw installation id returned by a browser redirect is never trusted.

The overview shows **connection state**, not a deployment safety score:

- active GitHub App installation
- repository/service mapping count
- observed runtime dependency edge count

The PR analysis view renders evidence coverage alongside findings. An empty finding list explicitly tells the user to inspect coverage instead of presenting a safe/pass conclusion.

## Hosted tenant authentication

Local/backward-compatible mode keeps tenant authentication disabled by default.

Hosted deployments should configure:

```bash
export TENANT_AUTH_ENABLED=true
export ADMIN_TOKEN='replace-with-a-long-random-secret'
```

Provision a tenant through the admin-only endpoint:

```http
POST /api/v1/admin/tenants
X-Admin-Token: <admin secret>
Content-Type: application/json

{
  "tenantId": "acme"
}
```

The response returns two credentials once:

```json
{
  "tenantId": "acme",
  "apiToken": "br_api_...",
  "ingestToken": "br_ingest_..."
}
```

Only SHA-256 hashes are persisted in product metadata. Provisioning the same tenant again rotates both credentials and invalidates the previous pair.

Use the API token for analysis, catalog, and telemetry-read APIs:

```http
Authorization: Bearer br_api_...
X-Tenant-ID: acme
```

Use the separate ingestion token for OTLP/telemetry writes:

```http
POST /api/v1/telemetry/otlp-json/v1/traces
Authorization: Bearer br_ingest_...
X-Tenant-ID: acme
```

When hosted auth is enabled, the bearer credential determines the tenant. If an `X-Tenant-ID` header is also supplied, it must match the credential or the request is rejected.

The admin provisioning endpoint returns 404 when no `ADMIN_TOKEN` is configured so it is not accidentally exposed in an unconfigured deployment.

## GitHub App setup for hosted mode

Hosted mode does not use a shared customer GitHub token. Each tenant binds a verified GitHub App installation and the backend mints short-lived installation access tokens.

Configure the GitHub App with:

```text
Request user authorization (OAuth) during installation: enabled

Callback URL:
https://<host>/api/v1/onboarding/github/callback

Webhook URL:
https://<host>/api/v1/github/webhook

Repository permissions:
Contents       read
Pull requests  read
Issues         read/write
Checks         read/write

Webhook events:
Pull request
Installation
```

Set the backend secrets:

```bash
export GITHUB_APP_CLIENT_ID='Iv1....'
export GITHUB_APP_CLIENT_SECRET='...'
export GITHUB_APP_PRIVATE_KEY='-----BEGIN RSA PRIVATE KEY-----\n...'
export GITHUB_APP_SLUG='your-app-slug'
export GITHUB_WEBHOOK_SECRET='long-random-webhook-secret'
export GITHUB_API_VERSION='2026-03-10'
```

Start installation with the tenant API token:

```http
POST /api/v1/onboarding/github/install
Authorization: Bearer br_api_...
X-Tenant-ID: acme
```

The response contains an `installUrl`. Send the user there. A high-entropy one-time `state` value binds the flow to the tenant.

After GitHub OAuth returns to the callback, the backend exchanges the code for a user token and lists installations that GitHub says the user can access. The callback never trusts a raw `installation_id` from a browser redirect.

If exactly one installation is visible, it is bound automatically. If multiple are visible, the callback returns candidates and one can be claimed with:

```http
POST /api/v1/onboarding/github/installations/{installationId}/claim
Authorization: Bearer br_api_...
X-Tenant-ID: acme
```

List bound installations:

```http
GET /api/v1/onboarding/github/installations
Authorization: Bearer br_api_...
X-Tenant-ID: acme
```

For `pull_request` actions `opened`, `synchronize`, and `reopened`, a valid signed webhook automatically:

```text
installation id
   ↓ tenant binding
short-lived installation token
   ↓
PR diff + base/head source
   ↓
blast-radius analysis
   ↓
update one bot-owned PR comment
   +
create a neutral "PR Blast Radius" check
```

Webhook payloads are verified against the raw request bytes with `X-Hub-Signature-256` / HMAC-SHA256. Delivery IDs are persisted so completed deliveries are idempotent; failed deliveries can be retried.

The check is deliberately `neutral`. Missing findings are not converted into a pass/safe verdict.

## Tenant isolation

Product metadata is partitioned by a normalized tenant id. Runtime dependency edges, retained trace spans, and repository → service mappings all include tenant scope in their keys.

Existing single-tenant integrations remain compatible through the built-in tenant:

```text
default
```

Tenant-aware API calls use:

```http
X-Tenant-ID: acme
```

For example:

```http
POST /api/v1/telemetry/otlp-json/v1/traces
X-Tenant-ID: acme

GET /api/v1/pr/acme/orders/42/impact
X-Tenant-ID: acme

PUT /api/v1/catalog/repositories/acme/orders
X-Tenant-ID: acme
```

The same service or repository name can therefore exist independently in multiple tenants.

Pre-tenant metadata is migrated once into the `default` tenant. Legacy tables are removed after successful migration so deleted data cannot be resurrected on a later restart.

The currently configured customer PostgreSQL datasource remains deployment-scoped. To prevent cross-customer evidence leakage, PostgreSQL runtime evidence is disabled for non-default tenants until tenant-specific database connections are implemented.

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

Local / legacy GitHub access when `TENANT_AUTH_ENABLED=false`:

```bash
export GITHUB_TOKEN=...
```

Hosted mode should instead configure the GitHub App variables above. When tenant authentication is enabled, hosted PR analysis requires a bound installation and does not fall back to the shared `GITHUB_TOKEN`.

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

STATIC_OUTBOUND
- AVAILABLE
- NO_DATA
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

## Static outbound API evidence

Changed Java source is also analyzed for deterministic outbound API dependencies.

Supported patterns currently include:

```java
restTemplate.postForObject(
    "http://payment-service/payments",
    request,
    Payment.class
);

restClient.post()
    .uri("/payments")
    .retrieve();

webClient.get()
    .uri("/inventory/{id}")
    .retrieve();

paymentClient.createPayment(orderId); // resolvable OpenFeign interface
```

For `RestClient` and `WebClient`, literal base URLs created through `builder().baseUrl(...)` and `create(...)` are supported. `RestTemplate.exchange(..., HttpMethod.X, ...)` is also normalized.

Same-class helper methods are followed recursively. For OpenFeign, imported client interfaces are resolved against the PR head revision when they live under the same standard Java source root. Literal `@FeignClient(name/value/path)` and literal Spring mapping annotations are composed into the same endpoint identity used by telemetry.

Example:

```text
OrderController#create
   ↓ static source
PaymentClient#createPayment
   ↓
payment-service [HTTP POST /payments]
```

Confidence fusion is deterministic:

```text
trace/runtime confirmed + static match → CONFIRMED
static only                           → STRONG
service-topology fallback + static   → STRONG
service topology only                → POSSIBLE
```

Static extraction intentionally skips dynamic URIs, property-driven service names, route constants, custom composed annotations, and unresolved client source instead of guessing.

The current static traversal follows the changed method plus same-class helper calls. It does not yet build a repository-wide Java call graph across arbitrary injected service classes.

## Current limitations

- OTLP/HTTP JSON is supported, but native protobuf OTLP is not.
- Repository/service mapping is explicit rather than inferred.
- Hosted tenant PostgreSQL evidence is not enabled yet; non-default tenants currently use static + telemetry evidence only.
- Source-aware endpoint ownership and static outbound extraction currently require literal route/service values. Custom composed annotations, path constants, property-driven client names, and dynamically constructed URLs are not resolved yet.
- Static call traversal is intra-class except for resolvable OpenFeign interface definitions; arbitrary cross-class service call chains are not followed yet.
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
