package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record RuntimeDependencyEdge(
        String sourceService,
        String targetService,
        long callCount,
        Instant lastSeen
) {}
