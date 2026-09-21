package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImpactAnalysisServiceTest {

    @Test
    void passesChangedEndpointsAndStaticCallsIntoImpactAnalysis() {
        DatabaseImpactService database = mock(DatabaseImpactService.class);
        RuntimeImpactService runtime = mock(RuntimeImpactService.class);
        StaticOutboundImpactService staticOutbound =
                mock(StaticOutboundImpactService.class);
        EvidenceCoverageService coverage =
                mock(EvidenceCoverageService.class);

        var staticCall = new StaticOutboundCall(
                "OrderController#create",
                "payment-service",
                "HTTP POST /payments",
                "OpenFeign",
                "paymentClient.createPayment(orderId)"
        );

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
                ),
                List.of(staticCall)
        );

        when(database.analyze(changeSet)).thenReturn(List.of());
        when(runtime.analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        )).thenReturn(List.of());
        when(staticOutbound.fuse(
                List.of(),
                List.of(staticCall)
        )).thenReturn(List.of());
        when(coverage.evaluate(
                changeSet,
                "orders-service",
                List.of()
        )).thenReturn(List.of());

        var service = new ImpactAnalysisService(
                database,
                runtime,
                staticOutbound,
                coverage
        );

        service.analyze(changeSet, "orders-service");

        verify(runtime).analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        );
        verify(staticOutbound).fuse(
                List.of(),
                List.of(staticCall)
        );
    }
}
