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

OpenTelemetry-derived observations
              |
              v
     Runtime Dependency Store
     calls + lastSeen + edges
              |
              v
     Cycle-safe graph traversal
              |
              +--------------------+
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

Normalized outbound OpenTelemetry `CLIENT` / `PRODUCER` observations create directed edges:

```text
checkout-service -> orders-service -> payment-service
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

The service dependency graph is intentionally in memory for the MVP. Persistent graph storage can be added only after validating that runtime blast-radius evidence is useful.

## Next engineering step

Replace normalized telemetry ingestion with an OpenTelemetry Collector adapter or native OTLP receiver, then persist dependency edges with a retention window.
