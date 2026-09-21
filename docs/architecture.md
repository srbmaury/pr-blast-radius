# MVP Architecture

```text
GitHub PR
   |
   v
PR Diff Ingestor
   |
   +------> Static Java dependency extractor
   |
   +------> PostgreSQL schema/query collector
   |
   +------> OpenTelemetry runtime dependency collector
                  |
                  v
           Dependency Graph
                  |
                  v
            Impact Engine
                  |
          +-------+-------+
          |               |
          v               v
   GitHub PR comment    Web UI
```

## Core entities

- **Change** — file, symbol, API, table, or column modified by a PR.
- **Dependency** — directed relationship between two components.
- **Evidence** — why the dependency exists: static reference, SQL usage, or runtime trace.
- **Finding** — affected component plus evidence and confidence.

## Confidence model

| Level | Meaning |
|---|---|
| CONFIRMED | Observed from production/runtime evidence |
| STRONG | Direct static, schema, or SQL reference |
| POSSIBLE | Inferred; must not block a deployment by itself |

## First milestone

Detect a PostgreSQL column change in a PR and identify:
1. Java code that references it.
2. SQL that reads/writes it.
3. Runtime services connected to the owning service.
4. Evidence for every reported impact.
