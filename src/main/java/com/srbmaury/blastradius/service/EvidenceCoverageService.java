package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.EvidenceCoverage;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EvidenceCoverageService {

    private static final int RUNTIME_DEPTH = 3;

    private final PostgresDependencyCollector postgresCollector;
    private final RuntimeDependencyService runtimeDependencyService;

    public EvidenceCoverageService(
            PostgresDependencyCollector postgresCollector,
            RuntimeDependencyService runtimeDependencyService
    ) {
        this.postgresCollector = postgresCollector;
        this.runtimeDependencyService = runtimeDependencyService;
    }

    public List<EvidenceCoverage> evaluate(
            PullRequestChangeSet changeSet,
            String rootService,
            List<ImpactFinding> findings
    ) {
        return List.of(
                postgresCoverage(changeSet, findings),
                runtimeCoverage(rootService)
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

    private EvidenceCoverage runtimeCoverage(String rootService) {
        if (rootService == null || rootService.isBlank()) {
            return new EvidenceCoverage(
                    EvidenceSource.SERVICE_RUNTIME,
                    EvidenceStatus.NOT_CONFIGURED,
                    "No repository-to-runtime-service mapping is configured"
            );
        }

        try {
            var graph = runtimeDependencyService.downstream(
                    rootService,
                    RUNTIME_DEPTH
            );

            if (graph.edges().isEmpty()) {
                return new EvidenceCoverage(
                        EvidenceSource.SERVICE_RUNTIME,
                        EvidenceStatus.NO_DATA,
                        "Runtime service is configured, but no downstream telemetry exists for "
                                + rootService
                );
            }

            return new EvidenceCoverage(
                    EvidenceSource.SERVICE_RUNTIME,
                    EvidenceStatus.AVAILABLE,
                    "Observed " + graph.edges().size()
                            + " downstream runtime edge(s) from "
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
