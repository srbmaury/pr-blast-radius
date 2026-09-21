# MVP Architecture

```text
                     GitHub PR
                         |
                         v
                  PR Diff Ingestor
                         |
              +----------+----------+
              |                     |
              v                     v
      Java / SQL changes     PostgreSQL evidence
                                    |
                             pg_stat_statements

OTLP/HTTP JSON traces
        |
        v
   OTLP JSON adapter
service.name + peer.service
        |
        v
 Runtime Dependency Store
 calls + lastSeen + edges
        |
        v
 Cycle-safe graph traversal
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

## Evidence model

### Change evidence

PR diffs provide explicit changed files, Java types, tables, and columns.

### Database runtime evidence

`pg_stat_statements` is queried for observed SQL using affected tables or columns. Only observed queries produce `CONFIRMED` findings.

### Runtime service evidence

OTLP/HTTP JSON client or producer spans create directed edges only when both attributes are available:

```text
resource service.name = orders-service
peer.service          = payment-service

orders-service -> payment-service
```

Each edge retains:

- total observed calls
- last-seen timestamp

Downstream traversal has a configurable depth limit and cycle protection.

## Service ownership

The MVP does not guess which runtime service belongs to a repository. The caller provides it explicitly:

```text
/impact?service=orders-service
```

A repository/service catalog can replace this later.

## Storage

PostgreSQL is the source for SQL runtime evidence.

The runtime dependency graph is intentionally in memory for the MVP. This avoids writing product metadata into the customer's production database.

## Next engineering step

Persist dependency edges in a separate product metadata store with a retention window. Native OTLP protobuf support can follow if validation shows the JSON adapter is insufficient.
