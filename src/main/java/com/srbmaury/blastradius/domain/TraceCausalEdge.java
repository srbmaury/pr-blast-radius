package com.srbmaury.blastradius.domain;

import java.time.Instant;
import java.util.Set;

public record TraceCausalEdge(
        String sourceService,
        String targetService,
        String endpoint,
        long callCount,
        long traceCount,
        Instant lastSeen,
        Set<String> rootEndpoints
) {}
