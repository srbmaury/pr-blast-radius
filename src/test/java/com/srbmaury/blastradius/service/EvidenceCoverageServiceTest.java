package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.EvidenceSource;
import com.srbmaury.blastradius.domain.EvidenceStatus;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import com.srbmaury.blastradius.domain.TraceCausalEdge;
import com.srbmaury.blastradius.domain.TraceCausalityResult;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceCoverageServiceTest {

    @Test
    void exposesMissingServiceAndTraceConfiguration() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var changeSet = endpointChangeSet();

        var coverage = service.evaluate(
                changeSet,
                null,
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.SERVICE_RUNTIME);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NOT_CONFIGURED);
                })
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.ENDPOINT_RUNTIME);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NOT_CONFIGURED);
                })
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.TRACE_PATH);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NOT_CONFIGURED);
                });
    }

    @Test
    void reportsTracePathAvailableWhenMatchingServerSpansExist() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);

        Set<String> endpoints = Set.of("HTTP POST /orders");

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
                        "HTTP POST /orders",
                        100,
                        Instant.parse("2026-09-21T10:00:00Z")
                )));

        when(traces.analyze("orders-service", endpoints))
                .thenReturn(new TraceCausalityResult(
                        "orders-service",
                        endpoints,
                        50,
                        45,
                        List.of(new TraceCausalEdge(
                                "orders-service",
                                "payment-service",
                                "HTTP POST /payments",
                                42,
                                40,
                                Instant.parse("2026-09-21T10:00:00Z"),
                                endpoints
                        ))
                ));

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var coverage = service.evaluate(
                endpointChangeSet(),
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.TRACE_PATH);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.AVAILABLE);
                    assertThat(item.detail())
                            .contains("50 endpoint SERVER span")
                            .contains("45 trace");
                });
    }

    @Test
    void reportsNoTraceDataSeparatelyFromRouteCoverage() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);

        Set<String> endpoints = Set.of("HTTP POST /orders");

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
                        "HTTP POST /orders",
                        100,
                        Instant.parse("2026-09-21T10:00:00Z")
                )));

        when(traces.analyze("orders-service", endpoints))
                .thenReturn(new TraceCausalityResult(
                        "orders-service",
                        endpoints,
                        0,
                        0,
                        List.of()
                ));

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var coverage = service.evaluate(
                endpointChangeSet(),
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.ENDPOINT_RUNTIME);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.AVAILABLE);
                })
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.TRACE_PATH);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NO_DATA);
                });
    }

    @Test
    void tracePathIsNotApplicableWithoutResolvedEndpointChange() {
        PostgresDependencyCollector postgres = mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
                        List.of()
                ));

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var coverage = service.evaluate(
                new PullRequestChangeSet("acme/orders#1", List.of()),
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(EvidenceSource.TRACE_PATH);
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NOT_APPLICABLE);
                });
    }

    private PullRequestChangeSet endpointChangeSet() {
        return new PullRequestChangeSet(
                "acme/orders#42",
                List.of(new DetectedChange(
                        ChangeKind.API_ENDPOINT,
                        ChangeOperation.MODIFIED,
                        "HTTP POST /orders",
                        "OrderController.java",
                        "changed lines belong to OrderController#create"
                ))
        );
    }

    @Test
    void reportsStaticOutboundCoverageWhenSourceDependencyIsResolved() {
        PostgresDependencyCollector postgres =
                mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime =
                mock(RuntimeDependencyService.class);
        TraceCausalityService traces =
                mock(TraceCausalityService.class);

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
                        List.of()
                ));

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var changeSet = new PullRequestChangeSet(
                "acme/orders#60",
                List.of(new DetectedChange(
                        ChangeKind.API_ENDPOINT,
                        ChangeOperation.MODIFIED,
                        "HTTP POST /orders",
                        "OrderController.java",
                        "changed lines belong to OrderController#create"
                )),
                List.of(new StaticOutboundCall(
                        "OrderController#create",
                        "payment-service",
                        "HTTP POST /payments",
                        "OpenFeign",
                        "paymentClient.createPayment(orderId)"
                ))
        );

        var coverage = service.evaluate(
                changeSet,
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(
                                    EvidenceSource.STATIC_OUTBOUND
                            );
                    assertThat(item.status())
                            .isEqualTo(
                                    EvidenceStatus.AVAILABLE
                            );
                    assertThat(item.detail())
                            .contains("1 static outbound");
                });
    }

    @Test
    void reportsNoStaticDataWithoutGuessingDynamicCalls() {
        PostgresDependencyCollector postgres =
                mock(PostgresDependencyCollector.class);
        RuntimeDependencyService runtime =
                mock(RuntimeDependencyService.class);
        TraceCausalityService traces =
                mock(TraceCausalityService.class);

        when(runtime.blastRadius("orders-service", 3))
                .thenReturn(new RuntimeBlastRadius(
                        "orders-service",
                        3,
                        List.of(),
                        List.of()
                ));

        var service = new EvidenceCoverageService(
                postgres,
                runtime,
                traces
        );

        var coverage = service.evaluate(
                endpointChangeSet(),
                "orders-service",
                List.of()
        );

        assertThat(coverage)
                .anySatisfy(item -> {
                    assertThat(item.source())
                            .isEqualTo(
                                    EvidenceSource.STATIC_OUTBOUND
                            );
                    assertThat(item.status())
                            .isEqualTo(EvidenceStatus.NO_DATA);
                });
    }

}
