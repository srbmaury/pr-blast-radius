package com.srbmaury.blastradius.domain;

public record DetectedChange(
        ChangeKind kind,
        ChangeOperation operation,
        String identifier,
        String file,
        String evidence
) {}
