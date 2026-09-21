package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.PostgresQueryEvidence;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseImpactServiceTest {

    @Test
    void marksObservedRuntimeUsageAsConfirmed() {
        PostgresDependencyCollector collector = mock(PostgresDependencyCollector.class);
        when(collector.findQueryUsage("orders", "status"))
                .thenReturn(List.of(new PostgresQueryEvidence(
                        "select status from orders where id = $1",
                        18420,
                        18420,
                        812.4
                )));

        var service = new DatabaseImpactService(collector);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#42",
                List.of(new DetectedChange(
                        ChangeKind.DATABASE_COLUMN,
                        ChangeOperation.REMOVED,
                        "orders.status",
                        "db/migrations/V42__drop_status.sql",
                        "DROP COLUMN status"
                ))
        );

        var findings = service.analyze(changeSet);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
            assertThat(finding.relationship()).contains("orders.status");
            assertThat(finding.evidence()).contains("calls=18420");
        });
    }
}
