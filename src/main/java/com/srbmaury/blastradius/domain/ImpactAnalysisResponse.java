package com.srbmaury.blastradius.domain;

import java.util.List;

public record ImpactAnalysisResponse(
        PullRequestChangeSet changeSet,
        List<ImpactFinding> findings,
        List<EvidenceCoverage> coverage
) {
    public ImpactAnalysisResponse(
            PullRequestChangeSet changeSet,
            List<ImpactFinding> findings
    ) {
        this(changeSet, findings, List.of());
    }
}
