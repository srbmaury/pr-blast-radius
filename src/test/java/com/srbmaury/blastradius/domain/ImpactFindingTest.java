package com.srbmaury.blastradius.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactFindingTest {

    @Test
    void preservesEvidenceAndConfidence() {
        var finding = new ImpactFinding(
                "refund-service",
                "reads orders.status",
                "runtime query observed",
                ImpactConfidence.CONFIRMED
        );

        assertThat(finding.confidence()).isEqualTo(ImpactConfidence.CONFIRMED);
        assertThat(finding.evidence()).isEqualTo("runtime query observed");
    }
}
