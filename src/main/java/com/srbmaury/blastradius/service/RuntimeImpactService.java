package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuntimeImpactService {

    private static final int DEFAULT_MAX_DEPTH = 3;

    private final RuntimeDependencyService dependencyService;

    public RuntimeImpactService(RuntimeDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    public List<ImpactFinding> analyze(String rootService) {
        if (rootService == null || rootService.isBlank()) {
            return List.of();
        }

        return dependencyService.downstream(rootService, DEFAULT_MAX_DEPTH)
                .edges()
                .stream()
                .map(edge -> new ImpactFinding(
                        edge.targetService(),
                        edge.sourceService() + " -> " + edge.targetService(),
                        "runtime calls=" + edge.callCount()
                                + ", lastSeen=" + edge.lastSeen(),
                        ImpactConfidence.CONFIRMED
                ))
                .toList();
    }
}
