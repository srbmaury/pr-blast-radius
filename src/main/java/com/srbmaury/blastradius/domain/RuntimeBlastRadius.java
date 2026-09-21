package com.srbmaury.blastradius.domain;

import java.util.List;

public record RuntimeBlastRadius(
        String rootService,
        int maxDepth,
        List<RuntimeDependencyEdge> callers,
        List<RuntimeDependencyEdge> dependencies
) {
    public int totalEdges() {
        return callers.size() + dependencies.size();
    }
}
