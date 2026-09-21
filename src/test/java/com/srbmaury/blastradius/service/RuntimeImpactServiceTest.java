package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.TraceCausalEdge;
import com.srbmaury.blastradius.domain.TraceCausalityResult;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeImpactServiceTest {

    @Test
    void usesTraceCausalDependenciesWhenMatchingEndpointTracesExist() {
        RuntimeDependencyService dependencies = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);
        Set<String> changedEndpoints = Set.of("HTTP POST /orders");

        when(dependencies.blastRadius(
                "orders-service",
                3,
                changedEndpoints
        )).thenReturn(new RuntimeBlastRadius(
                "orders-service",
                3,
                List.of(new RuntimeDependencyEdge(
                        "checkout-service",
                        "orders-service",
                        "HTTP POST /orders",
                        12000,
                        Instant.parse("2026-09-21T10:15:00Z")
                )),
                List.of(
                        new RuntimeDependencyEdge(
                                "orders-service",
                                "payment-service",
                                "HTTP POST /payments",
                                8241,
                                Instant.parse("2026-09-21T10:15:00Z")
                        ),
                        new RuntimeDependencyEdge(
                                "orders-service",
                                "email-service",
                                "HTTP POST /notify",
                                400,
                                Instant.parse("2026-09-21T10:15:00Z")
                        )
                )
        ));

        when(traces.analyze(
                "orders-service",
                changedEndpoints
        )).thenReturn(new TraceCausalityResult(
                "orders-service",
                changedEndpoints,
                100,
                95,
                List.of(new TraceCausalEdge(
                        "orders-service",
                        "payment-service",
                        "HTTP POST /payments",
                        90,
                        88,
                        Instant.parse("2026-09-21T10:15:00Z"),
                        changedEndpoints
                ))
        ));

        var service = new RuntimeImpactService(dependencies, traces);
        var findings = service.analyze(
                "orders-service",
                changedEndpoints
        );

        assertThat(findings)
                .hasSize(2)
                .anySatisfy(finding -> {
                    assertThat(finding.component()).isEqualTo("checkout-service");
                    assertThat(finding.confidence())
                            .isEqualTo(ImpactConfidence.CONFIRMED);
                })
                .anySatisfy(finding -> {
                    assertThat(finding.component()).isEqualTo("payment-service");
                    assertThat(finding.relationship())
                            .contains("trace-causal path");
                    assertThat(finding.evidence())
                            .contains("traces=88");
                    assertThat(finding.confidence())
                            .isEqualTo(ImpactConfidence.CONFIRMED);
                })
                .noneSatisfy(finding ->
                        assertThat(finding.component())
                                .isEqualTo("email-service")
                );
    }

    @Test
    void downgradesServiceLevelDependenciesWhenTracePathIsMissing() {
        RuntimeDependencyService dependencies = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);
        Set<String> changedEndpoints = Set.of("HTTP POST /orders");

        when(dependencies.blastRadius(
                "orders-service",
                3,
                changedEndpoints
        )).thenReturn(new RuntimeBlastRadius(
                "orders-service",
                3,
                List.of(),
                List.of(new RuntimeDependencyEdge(
                        "orders-service",
                        "payment-service",
                        "HTTP POST /payments",
                        8241,
                        Instant.parse("2026-09-21T10:15:00Z")
                ))
        ));

        when(traces.analyze(
                "orders-service",
                changedEndpoints
        )).thenReturn(new TraceCausalityResult(
                "orders-service",
                changedEndpoints,
                0,
                0,
                List.of()
        ));

        var service = new RuntimeImpactService(dependencies, traces);
        var findings = service.analyze(
                "orders-service",
                changedEndpoints
        );

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.component()).isEqualTo("payment-service");
            assertThat(finding.relationship())
                    .contains("service-level fallback");
            assertThat(finding.confidence())
                    .isEqualTo(ImpactConfidence.POSSIBLE);
        });
    }

    @Test
    void keepsServiceLevelDependenciesConfirmedWithoutEndpointScope() {
        RuntimeDependencyService dependencies = mock(RuntimeDependencyService.class);
        TraceCausalityService traces = mock(TraceCausalityService.class);

        when(dependencies.blastRadius(
                "orders-service",
                3,
                Set.of()
        )).thenReturn(new RuntimeBlastRadius(
                "orders-service",
                3,
                List.of(),
                List.of(new RuntimeDependencyEdge(
                        "orders-service",
                        "payment-service",
                        "HTTP POST /payments",
                        8241,
                        Instant.parse("2026-09-21T10:15:00Z")
                ))
        ));

        when(traces.analyze(
                "orders-service",
                Set.of()
        )).thenReturn(new TraceCausalityResult(
                "orders-service",
                Set.of(),
                0,
                0,
                List.of()
        ));

        var service = new RuntimeImpactService(dependencies, traces);

        assertThat(service.analyze("orders-service"))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.confidence())
                                .isEqualTo(ImpactConfidence.CONFIRMED)
                );
    }
}
