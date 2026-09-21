package com.srbmaury.blastradius.domain;

import java.util.List;

public record ImpactAnalysisResponse(
        PullRequestChangeSet changeSet,
        List<ImpactFinding> findings
) {}
