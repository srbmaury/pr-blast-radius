package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.TraceCausalEdge;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
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
    private final TraceCausalityService traceCausalityService;

    public RuntimeImpactService(
            RuntimeDependencyService dependencyService,
            TraceCausalityService traceCausalityService
    ) {
        this.dependencyService = dependencyService;
        this.traceCausalityService = traceCausalityService;
    }

    public List<ImpactFinding> analyze(String rootService) {
        return analyze(rootService, Set.of());
    }

    public List<ImpactFinding> analyze(
            String rootService,
            Set<String> changedEndpoints
    ) {
        if (rootService == null || rootService.isBlank()) {
            return List.of();
        }

        try {
            var radius = dependencyService.blastRadius(
                    rootService,
                    DEFAULT_MAX_DEPTH,
                    changedEndpoints
            );

            var traceCausality = traceCausalityService.analyze(
                    rootService,
                    changedEndpoints
            );

            List<ImpactFinding> findings = new ArrayList<>();
            Set<EdgeKey> seenEdges = new HashSet<>();

            addCallerFindings(
                    radius.callers(),
                    findings,
                    seenEdges
            );

            if (changedEndpoints != null
                    && !changedEndpoints.isEmpty()
                    && traceCausality.hasMatchingTracePath()) {
                addTraceCausalDependencyFindings(
                        traceCausality.downstreamEdges(),
                        findings,
                        seenEdges
                );
            } else if (changedEndpoints != null
                    && !changedEndpoints.isEmpty()) {
                addServiceLevelDependencyFindings(
                        radius.dependencies(),
                        findings,
                        seenEdges,
                        ImpactConfidence.POSSIBLE,
                        "service-level fallback; no matching trace-path evidence"
                );
            } else {
                addServiceLevelDependencyFindings(
                        radius.dependencies(),
                        findings,
                        seenEdges,
                        ImpactConfidence.CONFIRMED,
                        "observed runtime dependency"
                );
            }

            return List.copyOf(findings);
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private void addCallerFindings(
            List<RuntimeDependencyEdge> callers,
            List<ImpactFinding> findings,
            Set<EdgeKey> seenEdges
    ) {
        callers.forEach(edge -> {
            EdgeKey key = EdgeKey.from(edge);

            if (!seenEdges.add(key)) {
                return;
            }

            findings.add(new ImpactFinding(
                    edge.sourceService(),
                    edge.sourceService()
                            + " -> "
                            + edge.targetService()
                            + endpointSuffix(edge.endpoint())
                            + " (caller path into changed service)",
                    "runtime calls=" + edge.callCount()
                            + ", lastSeen=" + edge.lastSeen(),
                    ImpactConfidence.CONFIRMED
            ));
        });
    }

    private void addTraceCausalDependencyFindings(
            List<TraceCausalEdge> edges,
            List<ImpactFinding> findings,
            Set<EdgeKey> seenEdges
    ) {
        edges.forEach(edge -> {
            EdgeKey key = new EdgeKey(
                    edge.sourceService(),
                    edge.targetService(),
                    edge.endpoint()
            );

            if (!seenEdges.add(key)) {
                return;
            }

            findings.add(new ImpactFinding(
                    edge.targetService(),
                    edge.sourceService()
                            + " -> "
                            + edge.targetService()
                            + endpointSuffix(edge.endpoint())
                            + " (trace-causal path from changed endpoint)",
                    "observed calls="
                            + edge.callCount()
                            + ", traces="
                            + edge.traceCount()
                            + ", rootEndpoints="
                            + edge.rootEndpoints()
                            + ", lastSeen="
                            + edge.lastSeen(),
                    ImpactConfidence.CONFIRMED
            ));
        });
    }

    private void addServiceLevelDependencyFindings(
            List<RuntimeDependencyEdge> dependencies,
            List<ImpactFinding> findings,
            Set<EdgeKey> seenEdges,
            ImpactConfidence confidence,
            String reason
    ) {
        dependencies.forEach(edge -> {
            EdgeKey key = EdgeKey.from(edge);

            if (!seenEdges.add(key)) {
                return;
            }

            findings.add(new ImpactFinding(
                    edge.targetService(),
                    edge.sourceService()
                            + " -> "
                            + edge.targetService()
                            + endpointSuffix(edge.endpoint())
                            + " ("
                            + reason
                            + ")",
                    "runtime calls=" + edge.callCount()
                            + ", lastSeen=" + edge.lastSeen(),
                    confidence
            ));
        });
    }

    private String endpointSuffix(String endpoint) {
        return endpoint == null
                || endpoint.isBlank()
                || "*".equals(endpoint)
                ? ""
                : " [" + endpoint + "]";
    }

    private record EdgeKey(
            String sourceService,
            String targetService,
            String endpoint
    ) {
        private static EdgeKey from(RuntimeDependencyEdge edge) {
            return new EdgeKey(
                    edge.sourceService(),
                    edge.targetService(),
                    edge.endpoint()
            );
        }
    }
}
