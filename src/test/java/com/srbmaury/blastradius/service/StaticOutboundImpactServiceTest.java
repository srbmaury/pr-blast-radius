package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaticOutboundImpactServiceTest {

    private final StaticOutboundImpactService service =
            new StaticOutboundImpactService();

    @Test
    void emitsStrongFindingWhenOnlyStaticEvidenceExists() {
        var calls = List.of(new StaticOutboundCall(
                "OrderService#create",
                "payment-service",
                "HTTP POST /payments",
                "RestClient",
                "paymentClient.post().uri(\"/payments\")"
        ));

        assertThat(service.fuse(List.of(), calls))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.component())
                            .isEqualTo("payment-service");
                    assertThat(finding.confidence())
                            .isEqualTo(ImpactConfidence.STRONG);
                    assertThat(finding.relationship())
                            .contains("HTTP POST /payments");
                });
    }

    @Test
    void augmentsConfirmedRuntimeFindingInsteadOfDuplicatingIt() {
        var runtime = List.of(new ImpactFinding(
                "payment-service",
                "orders-service -> payment-service [HTTP POST /payments] (trace-causal path from changed endpoint)",
                "observed calls=90, traces=88",
                ImpactConfidence.CONFIRMED
        ));

        var calls = List.of(new StaticOutboundCall(
                "OrderService#create",
                "payment-service",
                "HTTP POST /payments",
                "OpenFeign",
                "paymentClient.createPayment(orderId)"
        ));

        assertThat(service.fuse(runtime, calls))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.confidence())
                            .isEqualTo(ImpactConfidence.CONFIRMED);
                    assertThat(finding.evidence())
                            .contains("traces=88")
                            .contains("static OpenFeign call");
                });
    }

    @Test
    void upgradesPossibleTopologyFindingToStrongWhenStaticCallMatches() {
        var runtime = List.of(new ImpactFinding(
                "payment-service",
                "orders-service -> payment-service [HTTP POST /payments] (service-level fallback; no matching trace-path evidence)",
                "runtime calls=8241",
                ImpactConfidence.POSSIBLE
        ));

        var calls = List.of(new StaticOutboundCall(
                "OrderService#create",
                "payment-service",
                "HTTP POST /payments",
                "RestTemplate",
                "restTemplate.postForObject(...)"
        ));

        assertThat(service.fuse(runtime, calls))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.confidence())
                            .isEqualTo(ImpactConfidence.STRONG);
                    assertThat(finding.evidence())
                            .contains("runtime calls=8241")
                            .contains("static RestTemplate call");
                });
    }
}
