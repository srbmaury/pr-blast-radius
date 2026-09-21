package com.srbmaury.blastradius.domain;

import java.util.List;

public record RuntimeDependencyGraph(
        String rootService,
        int maxDepth,
        List<RuntimeDependencyEdge> edges
) {}
