package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeImpactServiceTest {

    @Test
    void labelsEndpointAwareCallersAndDependenciesSeparately() {
        RuntimeDependencyService dependencies = mock(RuntimeDependencyService.class);
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
                List.of(new RuntimeDependencyEdge(
                        "orders-service",
                        "payment-service",
                        "HTTP POST /payments",
                        8241,
                        Instant.parse("2026-09-21T10:15:00Z")
                ))
        ));

        var service = new RuntimeImpactService(dependencies);
        var findings = service.analyze(
                "orders-service",
                changedEndpoints
        );

        assertThat(findings).hasSize(2)
                .anySatisfy(finding -> {
                    assertThat(finding.component()).isEqualTo("checkout-service");
                    assertThat(finding.relationship())
                            .contains("HTTP POST /orders")
                            .contains("caller path into changed service");
                    assertThat(finding.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
                })
                .anySatisfy(finding -> {
                    assertThat(finding.component()).isEqualTo("payment-service");
                    assertThat(finding.relationship())
                            .contains("HTTP POST /payments")
                            .contains("dependency path from changed service");
                    assertThat(finding.evidence()).contains("runtime calls=8241");
                });
    }
}
