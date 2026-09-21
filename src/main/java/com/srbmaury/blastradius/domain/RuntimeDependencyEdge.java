package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record RuntimeDependencyEdge(
        String sourceService,
        String targetService,
        String endpoint,
        long callCount,
        Instant lastSeen
) {
    public RuntimeDependencyEdge(
            String sourceService,
            String targetService,
            long callCount,
            Instant lastSeen
    ) {
        this(sourceService, targetService, "*", callCount, lastSeen);
    }
}
