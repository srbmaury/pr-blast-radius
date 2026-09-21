package com.srbmaury.blastradius.domain;

public record ImpactFinding(
        String component,
        String relationship,
        String evidence,
        ImpactConfidence confidence
) {}
