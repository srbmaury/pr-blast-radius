package com.srbmaury.blastradius.domain;

import java.util.List;
import java.util.Set;

public record TraceCausalityResult(
        String rootService,
        Set<String> rootEndpoints,
        long matchedRootSpanCount,
        long matchedTraceCount,
        List<TraceCausalEdge> downstreamEdges
) {
    public boolean hasMatchingTracePath() {
        return matchedRootSpanCount > 0;
    }
}
