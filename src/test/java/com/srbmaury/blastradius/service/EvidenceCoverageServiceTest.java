package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
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

    @Test
    void reportsUnavailableEndpointCoverageWhenCallerRoutesAreUnknown() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
                        List.of()
                ));
        when(runtime.directCallers("orders-service"))
                .thenReturn(List.of(new RuntimeDependencyEdge(
                        "checkout-service",
                        "orders-service",
                        "*",
                        100,
                        Instant.parse("2026-09-21T10:00:00Z")
                )));

        var service = new EvidenceCoverageService(postgres, runtime);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#50",
                List.of(new DetectedChange(
                        ChangeKind.API_ENDPOINT,
                        ChangeOperation.MODIFIED,
                        "HTTP POST /orders",
                        "OrderController.java",
                        "@PostMapping(\"/orders\")"
                ))
        );

        var coverage = service.evaluate(
                changeSet,
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.ENDPOINT_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
                    assertThat(item.detail()).contains("endpoint identity is missing");
                });
    }

    @Test
    void reportsAvailableEndpointCoverageWhenChangedRouteHasObservedCaller() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
                        List.of()
                ));
        when(runtime.directCallers("orders-service"))
                .thenReturn(List.of(
                        new RuntimeDependencyEdge(
                                "checkout-service",
                                "orders-service",
                                "HTTP POST /orders",
                                500,
                                Instant.parse("2026-09-21T10:00:00Z")
                        ),
                        new RuntimeDependencyEdge(
                                "admin-service",
                                "orders-service",
                                "HTTP GET /orders/{id}",
                                20,
                                Instant.parse("2026-09-21T10:00:00Z")
                        )
                ));

        var service = new EvidenceCoverageService(postgres, runtime);
        var changeSet = new PullRequestChangeSet(
                "acme/orders#51",
                List.of(new DetectedChange(
                        ChangeKind.API_ENDPOINT,
                        ChangeOperation.MODIFIED,
                        "HTTP POST /orders",
                        "OrderController.java",
                        "@PostMapping(\"/orders\")"
                ))
        );

        var coverage = service.evaluate(
                changeSet,
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(EvidenceSource.ENDPOINT_RUNTIME);
                    assertThat(item.status()).isEqualTo(EvidenceStatus.AVAILABLE);
                    assertThat(item.detail()).contains("HTTP POST /orders");
                });
    }

}
