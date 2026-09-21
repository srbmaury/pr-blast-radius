package com.srbmaury.blastradius.domain;

public record EvidenceCoverage(
        EvidenceSource source,
        EvidenceStatus status,
        String detail
) {}
