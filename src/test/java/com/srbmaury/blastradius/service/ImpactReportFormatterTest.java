package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
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
    void formatsChangesAndProductionEvidence() {
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
                ))
        );

        String report = formatter.toMarkdown(response);

        assertThat(report)
                .contains("PR Blast Radius")
                .contains("orders.status")
                .contains("payment-service")
                .contains("CONFIRMED")
                .contains("runtime calls=8241");
    }

    @Test
    void statesWhenNoProductionEvidenceIsAvailable() {
        var response = new ImpactAnalysisResponse(
                new PullRequestChangeSet("raw-diff", List.of()),
                List.of()
        );

        assertThat(formatter.toMarkdown(response))
                .contains("No confirmed production impact found");
    }
}
