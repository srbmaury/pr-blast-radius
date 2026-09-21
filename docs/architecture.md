# Architecture

```text
                     GitHub PR
                         |
                         v
                  PR Diff Ingestor
                         |
              +----------+----------+
              |                     |
              v                     v
      Java / SQL changes     Customer PostgreSQL
                                    |
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
 runtime_dependency_edge
 calls + lastSeen
        |
        +--> scheduled retention cleanup
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

## Database isolation

Customer PostgreSQL is treated as an evidence source. The product does not create its own tables there.

A separate metadata datasource owns product state such as runtime dependency edges. Local development defaults to H2 file storage; production can point the metadata datasource at a dedicated database.

## Runtime edge persistence

Each directed edge stores:

- source service
- target service
- observed call count
- last-seen timestamp

The pair `(source_service, target_service)` is the primary key.

Repeated observations increment the call count and advance `last_seen`.

## Retention

A scheduled cleanup removes edges whose `last_seen` falls outside the configured retention window.

Default:

```text
retention: 168 hours
cleanup: every 1 hour
```

This prevents old topology from permanently polluting blast-radius results.

## Evidence model

Only observed PostgreSQL queries or runtime service edges become `CONFIRMED` findings. Missing telemetry results in missing evidence rather than guessed dependencies.

## Next focused capability

Build a repository/service catalog so callers no longer have to manually pass `?service=orders-service`.
