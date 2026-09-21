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
PR Diff Ingestor          service resolution
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
 + runtime_dependency_edge
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

Each edge stores source service, target service, observed call count, and last-seen timestamp.

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

Add repository bootstrap/discovery so a new installation can populate service mappings from configuration or repository metadata without manually calling the catalog API one repository at a time.
