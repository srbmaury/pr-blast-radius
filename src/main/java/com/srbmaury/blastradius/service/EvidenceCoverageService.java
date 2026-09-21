package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.EvidenceCoverage;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class EvidenceCoverageService {

    private static final int RUNTIME_DEPTH = 3;

    private final PostgresDependencyCollector postgresCollector;
    private final RuntimeDependencyService runtimeDependencyService;
    private final TraceCausalityService traceCausalityService;

    public EvidenceCoverageService(
            PostgresDependencyCollector postgresCollector,
            RuntimeDependencyService runtimeDependencyService,
            TraceCausalityService traceCausalityService
    ) {
        this.postgresCollector = postgresCollector;
        this.runtimeDependencyService = runtimeDependencyService;
        this.traceCausalityService = traceCausalityService;
    }

    public List<EvidenceCoverage> evaluate(
            PullRequestChangeSet changeSet,
            String rootService,
            List<ImpactFinding> findings
    ) {
        return List.of(
                postgresCoverage(changeSet, findings),
                runtimeCoverage(rootService),
                endpointCoverage(changeSet, rootService),
                tracePathCoverage(changeSet, rootService)
        );
    }

    private EvidenceCoverage postgresCoverage(
            PullRequestChangeSet changeSet,
            List<ImpactFinding> findings
    ) {
        boolean hasDatabaseChange = changeSet.changes().stream()
                .anyMatch(change -> change.kind() == ChangeKind.DATABASE_TABLE
                        || change.kind() == ChangeKind.DATABASE_COLUMN);

        if (!hasDatabaseChange) {
            return new EvidenceCoverage(
                    EvidenceSource.POSTGRES_RUNTIME,
                    EvidenceStatus.NOT_APPLICABLE,
                    "PR contains no detected PostgreSQL table or column change"
            );
        }

        if (!postgresCollector.isAvailable()) {
            return new EvidenceCoverage(
                    EvidenceSource.POSTGRES_RUNTIME,
                    EvidenceStatus.UNAVAILABLE,
                    "pg_stat_statements is unavailable or customer PostgreSQL cannot be queried"
            );
        }

        boolean foundRuntimeUsage = findings.stream()
                .anyMatch(finding -> "PostgreSQL".equals(finding.component()));

        if (!foundRuntimeUsage) {
            return new EvidenceCoverage(
                    EvidenceSource.POSTGRES_RUNTIME,
                    EvidenceStatus.NO_DATA,
                    "PostgreSQL runtime source is available but no matching observed query was found"
            );
        }

        return new EvidenceCoverage(
                EvidenceSource.POSTGRES_RUNTIME,
                EvidenceStatus.AVAILABLE,
                "Matching runtime SQL evidence was found"
        );
    }

    private EvidenceCoverage endpointCoverage(
            PullRequestChangeSet changeSet,
            String rootService
    ) {
        List<String> changedEndpoints = changeSet.changes().stream()
                .filter(change -> change.kind() == ChangeKind.API_ENDPOINT)
                .map(change -> change.identifier())
                .distinct()
                .toList();

        if (changedEndpoints.isEmpty()) {
            return new EvidenceCoverage(
                    EvidenceSource.ENDPOINT_RUNTIME,
                    EvidenceStatus.NOT_APPLICABLE,
                    "PR contains no resolved API endpoint change"
            );
        }

        if (rootService == null || rootService.isBlank()) {
            return new EvidenceCoverage(
                    EvidenceSource.ENDPOINT_RUNTIME,
                    EvidenceStatus.NOT_CONFIGURED,
                    "Endpoint filtering requires a repository-to-runtime-service mapping"
            );
        }

        try {
            var callers = runtimeDependencyService.directCallers(rootService);

            if (callers.isEmpty()) {
                return new EvidenceCoverage(
                        EvidenceSource.ENDPOINT_RUNTIME,
                        EvidenceStatus.NO_DATA,
                        "No direct caller telemetry exists for " + rootService
                );
            }

            long routeAware = callers.stream()
                    .filter(edge -> edge.endpoint() != null)
                    .filter(edge -> !"*".equals(edge.endpoint()))
                    .count();

            if (routeAware == 0) {
                return new EvidenceCoverage(
                        EvidenceSource.ENDPOINT_RUNTIME,
                        EvidenceStatus.UNAVAILABLE,
                        "Caller telemetry exists, but endpoint identity is missing on every direct edge"
                );
            }

            long matching = callers.stream()
                    .filter(edge -> changedEndpoints.contains(edge.endpoint()))
                    .count();

            if (matching > 0) {
                return new EvidenceCoverage(
                        EvidenceSource.ENDPOINT_RUNTIME,
                        EvidenceStatus.AVAILABLE,
                        "Matched "
                                + matching
                                + " direct caller edge(s) to changed endpoint(s): "
                                + String.join(", ", changedEndpoints)
                );
            }

            long unknown = callers.size() - routeAware;
            String suffix = unknown > 0
                    ? "; " + unknown + " direct caller edge(s) lack endpoint identity"
                    : "";

            return new EvidenceCoverage(
                    EvidenceSource.ENDPOINT_RUNTIME,
                    EvidenceStatus.NO_DATA,
                    "Route-aware telemetry is available, but no direct caller matched changed endpoint(s): "
                            + String.join(", ", changedEndpoints)
                            + suffix
            );
        } catch (DataAccessException ex) {
            return new EvidenceCoverage(
                    EvidenceSource.ENDPOINT_RUNTIME,
                    EvidenceStatus.UNAVAILABLE,
                    "Runtime dependency metadata store is unavailable"
            );
        }
    }

    private EvidenceCoverage tracePathCoverage(
            PullRequestChangeSet changeSet,
            String rootService
    ) {
        Set<String> changedEndpoints = changeSet.changes().stream()
                .filter(change -> change.kind() == ChangeKind.API_ENDPOINT)
                .map(change -> change.identifier())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        if (changedEndpoints.isEmpty()) {
            return new EvidenceCoverage(
                    EvidenceSource.TRACE_PATH,
                    EvidenceStatus.NOT_APPLICABLE,
                    "PR contains no resolved API endpoint change"
            );
        }

        if (rootService == null || rootService.isBlank()) {
            return new EvidenceCoverage(
                    EvidenceSource.TRACE_PATH,
                    EvidenceStatus.NOT_CONFIGURED,
                    "Trace-path analysis requires a repository-to-runtime-service mapping"
            );
        }

        try {
            var result = traceCausalityService.analyze(
                    rootService,
                    changedEndpoints
            );

            if (!result.hasMatchingTracePath()) {
                return new EvidenceCoverage(
                        EvidenceSource.TRACE_PATH,
                        EvidenceStatus.NO_DATA,
                        "No retained SERVER spans matched changed endpoint(s): "
                                + String.join(", ", changedEndpoints)
                );
            }

            return new EvidenceCoverage(
                    EvidenceSource.TRACE_PATH,
                    EvidenceStatus.AVAILABLE,
                    "Matched "
                            + result.matchedRootSpanCount()
                            + " endpoint SERVER span(s) across "
                            + result.matchedTraceCount()
                            + " trace(s); observed "
                            + result.downstreamEdges().size()
                            + " causal downstream edge(s)"
            );
        } catch (DataAccessException ex) {
            return new EvidenceCoverage(
                    EvidenceSource.TRACE_PATH,
                    EvidenceStatus.UNAVAILABLE,
                    "Trace lineage metadata store is unavailable"
            );
        }
    }

    private EvidenceCoverage runtimeCoverage(String rootService) {
        if (rootService == null || rootService.isBlank()) {
            return new EvidenceCoverage(
                    EvidenceSource.SERVICE_RUNTIME,
                    EvidenceStatus.NOT_CONFIGURED,
                    "No repository-to-runtime-service mapping is configured"
            );
        }

        try {
            var radius = runtimeDependencyService.blastRadius(
                    rootService,
                    RUNTIME_DEPTH
            );

            if (radius.totalEdges() == 0) {
                return new EvidenceCoverage(
                        EvidenceSource.SERVICE_RUNTIME,
                        EvidenceStatus.NO_DATA,
                        "Runtime service is configured, but no caller or dependency telemetry exists for "
                                + rootService
                );
            }

            return new EvidenceCoverage(
                    EvidenceSource.SERVICE_RUNTIME,
                    EvidenceStatus.AVAILABLE,
                    "Observed "
                            + radius.callers().size()
                            + " caller edge(s) and "
                            + radius.dependencies().size()
                            + " dependency edge(s) around "
                            + rootService
            );
        } catch (DataAccessException ex) {
            return new EvidenceCoverage(
                    EvidenceSource.SERVICE_RUNTIME,
                    EvidenceStatus.UNAVAILABLE,
                    "Runtime dependency metadata store is unavailable"
            );
        }
    }
}
