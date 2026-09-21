package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

@Service
public class RuntimeDependencyService {

    private static final Set<String> OUTBOUND_SPAN_KINDS = Set.of("CLIENT", "PRODUCER");

    private final RuntimeDependencyStore store;

    public RuntimeDependencyService(RuntimeDependencyStore store) {
        this.store = store;
    }

    public boolean ingest(OpenTelemetrySpanObservation observation) {
        if (observation == null) {
            return false;
        }

        String source = normalizeService(observation.sourceService());
        String target = normalizeService(observation.targetService());
        String kind = observation.spanKind() == null
                ? ""
                : observation.spanKind().trim().toUpperCase(Locale.ROOT);

        if (source == null
                || target == null
                || source.equals(target)
                || !OUTBOUND_SPAN_KINDS.contains(kind)) {
            return false;
        }

        Instant observedAt = observation.observedAt() == null
                ? Instant.now()
                : observation.observedAt();

        store.record(
                source,
                target,
                normalizeEndpoint(observation.endpoint()),
                observedAt
        );
        return true;
    }

    public long ingest(List<OpenTelemetrySpanObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return 0;
        }

        return observations.stream()
                .filter(this::ingest)
                .count();
    }

    public RuntimeDependencyGraph downstream(String rootService, int maxDepth) {
        String root = requireService(rootService);
        int depthLimit = Math.max(1, Math.min(maxDepth, 10));

        Set<RuntimeDependencyEdge> collected = new LinkedHashSet<>();
        Set<String> expanded = new HashSet<>();
        Queue<ServiceAtDepth> queue = new ArrayDeque<>();

        queue.add(new ServiceAtDepth(root, 0));
        expanded.add(root);

        while (!queue.isEmpty()) {
            ServiceAtDepth current = queue.remove();

            if (current.depth() >= depthLimit) {
                continue;
            }

            for (RuntimeDependencyEdge edge : store.outgoing(current.service())) {
                collected.add(edge);

                if (expanded.add(edge.targetService())) {
                    queue.add(new ServiceAtDepth(
                            edge.targetService(),
                            current.depth() + 1
                    ));
                }
            }
        }

        return new RuntimeDependencyGraph(
                root,
                depthLimit,
                new ArrayList<>(collected)
        );
    }

    public RuntimeDependencyGraph callers(String rootService, int maxDepth) {
        return callers(rootService, maxDepth, Set.of());
    }

    public RuntimeDependencyGraph callers(
            String rootService,
            int maxDepth,
            Set<String> changedEndpoints
    ) {
        String root = requireService(rootService);
        int depthLimit = Math.max(1, Math.min(maxDepth, 10));
        Set<String> endpointFilter = normalizeEndpoints(changedEndpoints);

        Set<RuntimeDependencyEdge> collected = new LinkedHashSet<>();
        Set<String> expanded = new HashSet<>();
        Queue<ServiceAtDepth> queue = new ArrayDeque<>();

        queue.add(new ServiceAtDepth(root, 0));
        expanded.add(root);

        while (!queue.isEmpty()) {
            ServiceAtDepth current = queue.remove();

            if (current.depth() >= depthLimit) {
                continue;
            }

            for (RuntimeDependencyEdge edge : store.incoming(current.service())) {
                if (current.depth() == 0
                        && !endpointFilter.isEmpty()
                        && !endpointFilter.contains(edge.endpoint())) {
                    continue;
                }

                collected.add(edge);

                if (expanded.add(edge.sourceService())) {
                    queue.add(new ServiceAtDepth(
                            edge.sourceService(),
                            current.depth() + 1
                    ));
                }
            }
        }

        return new RuntimeDependencyGraph(
                root,
                depthLimit,
                new ArrayList<>(collected)
        );
    }

    public RuntimeBlastRadius blastRadius(String rootService, int maxDepth) {
        return blastRadius(rootService, maxDepth, Set.of());
    }

    public RuntimeBlastRadius blastRadius(
            String rootService,
            int maxDepth,
            Set<String> changedEndpoints
    ) {
        RuntimeDependencyGraph callers = callers(
                rootService,
                maxDepth,
                changedEndpoints
        );
        RuntimeDependencyGraph dependencies = downstream(rootService, maxDepth);

        return new RuntimeBlastRadius(
                callers.rootService(),
                callers.maxDepth(),
                callers.edges(),
                dependencies.edges()
        );
    }

    public List<RuntimeDependencyEdge> directCallers(String service) {
        return store.incoming(requireService(service));
    }

    public List<RuntimeDependencyEdge> allEdges() {
        return store.all();
    }

    private String requireService(String service) {
        String normalized = normalizeService(service);
        if (normalized == null) {
            throw new IllegalArgumentException("Service name is required");
        }
        return normalized;
    }

    private String normalizeService(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        return service.trim();
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "*" : endpoint.trim();
    }

    private Set<String> normalizeEndpoints(Set<String> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return Set.of();
        }

        return endpoints.stream()
                .map(this::normalizeEndpoint)
                .filter(endpoint -> !"*".equals(endpoint))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record ServiceAtDepth(String service, int depth) {}
}
