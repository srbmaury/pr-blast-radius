package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceCoverageServiceTest {

    @Test
    void exposesUnavailableDatabaseAndMissingServiceMapping() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);

        when(postgres.isAvailable()).thenReturn(false);

        var service = new EvidenceCoverageService(postgres, runtime);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#42",
                List.of(new DetectedChange(
                        ChangeKind.DATABASE_COLUMN,
                        ChangeOperation.REMOVED,
                        "orders.status",
                        "db/V42.sql",
                        "DROP COLUMN status"
                ))
        );

        var coverage = service.evaluate(changeSet, null, List.of());

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.POSTGRES_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
                })
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.SERVICE_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.NOT_CONFIGURED);
                });
    }

    @Test
    void distinguishesConnectedSourceWithNoRuntimeData() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);

        when(runtime.downstream("orders-service", 3))
                .thenReturn(new RuntimeDependencyGraph(
                        "orders-service",
                        3,
                        List.of()
                ));

        var service = new EvidenceCoverageService(postgres, runtime);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#42",
                List.of()
        );

        var coverage = service.evaluate(
                changeSet,
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.POSTGRES_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.NOT_APPLICABLE);
                })
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.SERVICE_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.NO_DATA);
                });
    }

    @Test
    void reportsAvailableDatabaseEvidenceWhenMatchingQueryExists() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);

        when(postgres.isAvailable()).thenReturn(true);

        var service = new EvidenceCoverageService(postgres, runtime);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#42",
                List.of(new DetectedChange(
                        ChangeKind.DATABASE_COLUMN,
                        ChangeOperation.REMOVED,
                        "orders.status",
                        "db/V42.sql",
                        "DROP COLUMN status"
                ))
        );

        var findings = List.of(new ImpactFinding(
                "PostgreSQL",
                "runtime query uses orders.status",
                "calls=100",
                ImpactConfidence.CONFIRMED
        ));

        var coverage = service.evaluate(changeSet, null, findings);

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.POSTGRES_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.AVAILABLE);
                });
    }
}
