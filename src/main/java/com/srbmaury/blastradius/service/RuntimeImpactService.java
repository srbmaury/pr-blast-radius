package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        try {
            var radius = dependencyService.blastRadius(
                    rootService,
                    DEFAULT_MAX_DEPTH
            );

            List<ImpactFinding> findings = new ArrayList<>();
            Set<EdgeKey> seenEdges = new HashSet<>();

            radius.callers().forEach(edge -> {
                if (!seenEdges.add(new EdgeKey(edge.sourceService(), edge.targetService()))) {
                    return;
                }

                findings.add(new ImpactFinding(
                        edge.sourceService(),
                        edge.sourceService()
                                + " -> "
                                + edge.targetService()
                                + " (caller path into changed service)",
                        "runtime calls=" + edge.callCount()
                                + ", lastSeen=" + edge.lastSeen(),
                        ImpactConfidence.CONFIRMED
                ));
            });

            radius.dependencies().forEach(edge -> {
                if (!seenEdges.add(new EdgeKey(edge.sourceService(), edge.targetService()))) {
                    return;
                }

                findings.add(new ImpactFinding(
                        edge.targetService(),
                        edge.sourceService()
                                + " -> "
                                + edge.targetService()
                                + " (dependency path from changed service)",
                        "runtime calls=" + edge.callCount()
                                + ", lastSeen=" + edge.lastSeen(),
                        ImpactConfidence.CONFIRMED
                ));
            });

            return List.copyOf(findings);
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private record EdgeKey(String sourceService, String targetService) {}
}
