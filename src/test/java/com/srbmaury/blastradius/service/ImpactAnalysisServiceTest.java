package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImpactAnalysisServiceTest {

    @Test
    void passesChangedEndpointsIntoRuntimeImpactAnalysis() {
        DatabaseImpactService database = mock(DatabaseImpactService.class);
        RuntimeImpactService runtime = mock(RuntimeImpactService.class);
        EvidenceCoverageService coverage = mock(EvidenceCoverageService.class);

        var changeSet = new PullRequestChangeSet(
                "acme/orders#52",
                List.of(
                        new DetectedChange(
                                ChangeKind.API_ENDPOINT,
                                ChangeOperation.MODIFIED,
                                "HTTP POST /orders",
                                "OrderController.java",
                                "@PostMapping(\"/orders\")"
                        ),
                        new DetectedChange(
                                ChangeKind.JAVA_TYPE,
                                ChangeOperation.MODIFIED,
                                "OrderController",
                                "OrderController.java",
                                "class OrderController"
                        )
                )
        );

        when(database.analyze(changeSet)).thenReturn(List.of());
        when(runtime.analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        )).thenReturn(List.of());
        when(coverage.evaluate(
                changeSet,
                "orders-service",
                List.of()
        )).thenReturn(List.of());

        var service = new ImpactAnalysisService(
                database,
                runtime,
                coverage
        );

        service.analyze(changeSet, "orders-service");

        verify(runtime).analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        );
    }
}
