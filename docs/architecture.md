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
   +--> Spring endpoint changes |
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
        v
 Dedicated metadata database
 + runtime_dependency_route_edge
 + repository_service_mapping
        |
        +--> scheduled retention cleanup
        |
        v
 Cycle-safe bidirectional graph traversal
 callers + dependencies
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

Downstream dependencies are intentionally not endpoint-filtered yet. Without trace-path causality, filtering them by the changed inbound route would overstate precision.

Legacy service-only edges migrate to endpoint `*`. Those edges remain useful for service-level topology but are not treated as precise endpoint matches.

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

Map arbitrary Java handler-body changes back to their owning Spring endpoint using source-aware parsing instead of relying only on changed mapping annotation lines.
