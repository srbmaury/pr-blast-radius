package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.EvidenceCoverage;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactReportFormatterTest {

    private final ImpactReportFormatter formatter = new ImpactReportFormatter();

    @Test
    void formatsChangesProductionEvidenceAndCoverage() {
        var response = new ImpactAnalysisResponse(
                new PullRequestChangeSet(
                        "acme/orders#42",
                        List.of(new DetectedChange(
                                ChangeKind.DATABASE_COLUMN,
                                ChangeOperation.REMOVED,
                                "orders.status",
                                "db/migrations/V42.sql",
                                "DROP COLUMN status"
                        ))
                ),
                List.of(new ImpactFinding(
                        "payment-service",
                        "orders-service -> payment-service",
                        "runtime calls=8241",
                        ImpactConfidence.CONFIRMED
                )),
                List.of(
                        new EvidenceCoverage(
                                EvidenceSource.POSTGRES_RUNTIME,
                                EvidenceStatus.AVAILABLE,
                                "Matching runtime SQL evidence was found"
                        ),
                        new EvidenceCoverage(
                                EvidenceSource.SERVICE_RUNTIME,
                                EvidenceStatus.AVAILABLE,
                                "Observed downstream runtime edge"
                        )
                )
        );

        String report = formatter.toMarkdown(response);

        assertThat(report)
                .contains("Evidence coverage")
                .contains("POSTGRES_RUNTIME")
                .contains("AVAILABLE")
                .contains("orders.status")
                .contains("payment-service")
                .contains("CONFIRMED")
                .contains("runtime calls=8241");
    }

    @Test
    void warnsWhenNoFindingsButCoverageIsIncomplete() {
        var response = new ImpactAnalysisResponse(
                new PullRequestChangeSet("raw-diff", List.of()),
                List.of(),
                List.of(
                        new EvidenceCoverage(
                                EvidenceSource.POSTGRES_RUNTIME,
                                EvidenceStatus.NOT_APPLICABLE,
                                "No DB change"
                        ),
                        new EvidenceCoverage(
                                EvidenceSource.SERVICE_RUNTIME,
                                EvidenceStatus.NOT_CONFIGURED,
                                "No repository/service mapping"
                        )
                )
        );

        assertThat(formatter.toMarkdown(response))
                .contains("coverage is incomplete")
                .contains("Do not treat this result as proof that the change is safe");
    }
}
