package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RuntimeDependencyStore {

    private final Map<EdgeKey, RuntimeDependencyEdge> edges = new ConcurrentHashMap<>();

    public void record(String sourceService, String targetService, Instant observedAt) {
        EdgeKey key = new EdgeKey(sourceService, targetService);

        edges.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new RuntimeDependencyEdge(
                        sourceService,
                        targetService,
                        1,
                        observedAt
                );
            }

            Instant lastSeen = existing.lastSeen().isAfter(observedAt)
                    ? existing.lastSeen()
                    : observedAt;

            return new RuntimeDependencyEdge(
                    sourceService,
                    targetService,
                    existing.callCount() + 1,
                    lastSeen
            );
        });
    }

    public List<RuntimeDependencyEdge> outgoing(String sourceService) {
        return edges.values().stream()
                .filter(edge -> edge.sourceService().equals(sourceService))
                .sorted(Comparator.comparing(RuntimeDependencyEdge::targetService))
                .toList();
    }

    public List<RuntimeDependencyEdge> all() {
        return edges.values().stream()
                .sorted(Comparator
                        .comparing(RuntimeDependencyEdge::sourceService)
                        .thenComparing(RuntimeDependencyEdge::targetService))
                .toList();
    }

    public void clear() {
        edges.clear();
    }

    private record EdgeKey(String sourceService, String targetService) {}
}
