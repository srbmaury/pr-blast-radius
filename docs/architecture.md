# Architecture

```text
Repository / service catalog
         |
         v
 GitHub repo -> runtime service
         |
         +----------------------+
                                |
GitHub PR                       |
   |                            |
   v                            v
PR Diff Ingestor                 service resolution
   |                            |
   +--> changed line ranges     |
   |         |                  |
   |         v                  |
   |   base/head Java source    |
   |         |                  |
   |         v                  |
   |    JavaParser AST          |
   |         |                  |
   |         v                  |
   |   owning Spring endpoint   |
   |                            |
   +----------+-----------------+
              |
      +-------+--------+
      |                |
      v                v
Java / SQL changes   Customer PostgreSQL
                     pg_stat_statements
                            |
                            v
                     runtime SQL evidence

OTLP/HTTP JSON traces
        |
        v
   OTLP JSON adapter
        |
        +-----------------------------+
        |                             |
        v                             v
 route dependency edges        short-lived trace spans
        |                       trace/span/parent IDs
        |                             |
        v                             v
 Dedicated metadata database   causal span-tree traversal
 + runtime_dependency_route_edge      |
 + repository_service_mapping         |
 + trace_span                         |
        |                             |
        +--> scheduled retention <----+
        |
        v
 Cycle-safe bidirectional graph traversal
 callers + service-level dependencies
        |
        +--------------------------+
                                   |
                                   v
                            Impact Analyzer
                                   |
                       +-----------+-----------+
                       |                       |
                       v                       v
                 JSON response          Markdown report
                                               |
                                               v
                                       GitHub PR comment
```

## Repository/service catalog

Repository ownership is stored explicitly in product metadata:

```text
acme/orders -> orders-service
acme/payments -> payment-service
```

PR analysis resolves the runtime service from the catalog automatically.

An explicit `?service=` parameter has higher precedence and can be used for temporary or canary mappings.

## Database isolation

Customer PostgreSQL is evidence-only.

Product-owned metadata lives in the separate metadata datasource:

- runtime dependency edges
- repository/service mappings
- future product configuration

## Runtime edge persistence

Each edge stores source service, target service, endpoint identity, observed call count, and last-seen timestamp. Endpoint identity is part of the primary key.

Stale edges are deleted according to the configured retention window.

## Bidirectional runtime graph

For a changed service, runtime analysis traverses both directions:

```text
callers                      dependencies

frontend -> checkout -> orders -> payment -> ledger
                        ^
                    changed service
```

Reverse traversal finds services that depend on the changed service. Forward traversal finds dependencies that the changed service invokes. Both directions are depth-limited and cycle-safe.

## Source-aware endpoint ownership

GitHub PR analysis parses unified-diff hunk coordinates into old and new changed line numbers.

For changed Java files, the analyzer fetches both revisions:

```text
base SHA -> old file -> old changed lines
head SHA -> new file -> new changed lines
```

JavaParser then maps those lines to the enclosing method. Spring class-level and method-level mappings are composed:

```java
@RequestMapping("/orders")
class OrderController {
    @GetMapping("/{id}")
    Order get(...) { ... }
}
```

becomes:

```text
HTTP GET /orders/{id}
```

If the same endpoint owns changed lines in both revisions, it is classified as `MODIFIED`. Direct mapping-annotation detection takes precedence for explicit route additions/removals. For deletion-only handler-body edits, the endpoint is conservatively treated as `MODIFIED` when the Java file still exists.

This analysis is intentionally syntax-based rather than symbol-resolution-based. Literal Spring mapping paths are supported; constants, custom composed annotations, and dynamic path construction are currently out of scope.

## Endpoint-aware caller correlation

Spring mapping changes are normalized as endpoint identities such as:

```text
HTTP POST /orders
HTTP GET /orders/{id}
```

OTLP client spans derive the same identity from low-cardinality client route metadata (`url.template` when available, with `http.route` accepted as a compatibility fallback). Runtime storage therefore distinguishes multiple routes between the same two services.

For a changed endpoint, only matching **direct incoming** edges seed caller traversal:

```text
frontend -> checkout -> orders
                       ^
                  HTTP POST /orders
```

The `checkout -> orders` edge must match the changed endpoint. The upstream `frontend -> checkout` edge does not need to use the same route because it represents a different hop.

Caller filtering remains route-aware through the aggregated runtime graph.

Downstream dependencies are now narrowed further when retained trace lineage exists. The changed endpoint's matching `SERVER` spans become causal roots, and only descendant outbound `CLIENT` / `PRODUCER` spans are treated as endpoint-causal downstream impact.

Legacy service-only edges migrate to endpoint `*`. Those edges remain useful for service-level topology but are not treated as precise endpoint matches.

## Trace-path causality

Trace lineage is stored separately from the longer-lived topology graph:

```text
trace_id
span_id
parent_span_id
service_name
target_service
endpoint
span_kind
observed_at
```

No request bodies, response bodies, or arbitrary OpenTelemetry attribute bags are persisted.

For a changed endpoint such as `HTTP POST /orders`, the analyzer:

```text
1. Finds retained SERVER spans:
   orders-service [HTTP POST /orders]

2. Loads all spans for those trace IDs.

3. Reconstructs parent → child relationships.

4. Walks descendants of each matching server span.

5. Aggregates descendant CLIENT / PRODUCER calls:
   source service
   target service
   outbound endpoint
   observed call count
   unique trace count
   last seen
```

Because remote `SERVER` spans normally inherit the propagated client span as parent, traversal can cross multiple synchronous services within one trace.

If at least one matching endpoint server span exists, causal downstream findings replace the broad downstream dependency set. If no matching trace path exists, the service-level downstream graph is retained as a fallback but those findings are downgraded to `POSSIBLE`.

Trace lineage defaults to 24-hour retention while the aggregated topology graph defaults to 7 days.

This is observational causality over retained traces, not exhaustive proof of all possible execution paths. Async producer→consumer relationships represented only by OpenTelemetry span links are not traversed in this version.

## Evidence model

Only observed SQL or runtime edges become `CONFIRMED` findings.

The analyzer separately reports **coverage** for each evidence source. This distinction is important:

```text
No findings + AVAILABLE coverage
    !=
No findings + UNAVAILABLE coverage
```

The first means the connected source was queried and did not provide matching evidence. The second means the system cannot make that claim because the evidence source was unavailable.

PR reports therefore warn explicitly when coverage is incomplete rather than presenting an empty finding set as a safety signal.

## Next focused capability

Add static outbound-call extraction for Java/Spring clients so endpoint-causal runtime evidence can be complemented by code-level API dependencies when production traces are sparse or sampled.
