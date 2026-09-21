package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeImpactServiceTest {

    @Test
    void turnsObservedRuntimeEdgesIntoConfirmedFindings() {
        RuntimeDependencyService dependencies = mock(RuntimeDependencyService.class);

        when(dependencies.downstream("orders-service", 3))
                .thenReturn(new RuntimeDependencyGraph(
                        "orders-service",
                        3,
                        List.of(new RuntimeDependencyEdge(
                                "orders-service",
                                "payment-service",
                                8241,
                                Instant.parse("2026-09-21T10:15:00Z")
                        ))
                ));

        var service = new RuntimeImpactService(dependencies);
        var findings = service.analyze("orders-service");

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.component()).isEqualTo("payment-service");
            assertThat(finding.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
            assertThat(finding.evidence()).contains("runtime calls=8241");
        });
    }
}
