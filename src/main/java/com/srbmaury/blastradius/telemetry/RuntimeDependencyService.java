package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.tenant.TenantIds;
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

    private static final Set<String> OUTBOUND_SPAN_KINDS =
            Set.of("CLIENT", "PRODUCER");

    private final RuntimeDependencyStore store;

    public RuntimeDependencyService(RuntimeDependencyStore store) {
        this.store = store;
    }

    public boolean ingest(OpenTelemetrySpanObservation observation) {
        return ingest(TenantIds.DEFAULT, observation);
    }

    public boolean ingest(
            String tenantId,
            OpenTelemetrySpanObservation observation
    ) {
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
                tenantId,
                source,
                target,
                normalizeEndpoint(observation.endpoint()),
                observedAt
        );
        return true;
    }

    public long ingest(List<OpenTelemetrySpanObservation> observations) {
        return ingest(TenantIds.DEFAULT, observations);
    }

    public long ingest(
            String tenantId,
            List<OpenTelemetrySpanObservation> observations
    ) {
        if (observations == null || observations.isEmpty()) {
            return 0;
        }

        return observations.stream()
                .filter(observation -> ingest(tenantId, observation))
                .count();
    }

    public RuntimeDependencyGraph downstream(
            String rootService,
            int maxDepth
    ) {
        return downstream(TenantIds.DEFAULT, rootService, maxDepth);
    }

    public RuntimeDependencyGraph downstream(
            String tenantId,
            String rootService,
            int maxDepth
    ) {
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

            for (RuntimeDependencyEdge edge :
                    store.outgoing(tenantId, current.service())) {
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

    public RuntimeDependencyGraph callers(
            String rootService,
            int maxDepth
    ) {
        return callers(
                TenantIds.DEFAULT,
                rootService,
                maxDepth,
                Set.of()
        );
    }

    public RuntimeDependencyGraph callers(
            String rootService,
            int maxDepth,
            Set<String> changedEndpoints
    ) {
        return callers(
                TenantIds.DEFAULT,
                rootService,
                maxDepth,
                changedEndpoints
        );
    }

    public RuntimeDependencyGraph callers(
            String tenantId,
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

            for (RuntimeDependencyEdge edge :
                    store.incoming(tenantId, current.service())) {
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

    public RuntimeBlastRadius blastRadius(
            String rootService,
            int maxDepth
    ) {
        return blastRadius(
                TenantIds.DEFAULT,
                rootService,
                maxDepth,
                Set.of()
        );
    }

    public RuntimeBlastRadius blastRadius(
            String rootService,
            int maxDepth,
            Set<String> changedEndpoints
    ) {
        return blastRadius(
                TenantIds.DEFAULT,
                rootService,
                maxDepth,
                changedEndpoints
        );
    }

    public RuntimeBlastRadius blastRadius(
            String tenantId,
            String rootService,
            int maxDepth,
            Set<String> changedEndpoints
    ) {
        RuntimeDependencyGraph callers = callers(
                tenantId,
                rootService,
                maxDepth,
                changedEndpoints
        );
        RuntimeDependencyGraph dependencies = downstream(
                tenantId,
                rootService,
                maxDepth
        );

        return new RuntimeBlastRadius(
                callers.rootService(),
                callers.maxDepth(),
                callers.edges(),
                dependencies.edges()
        );
    }

    public List<RuntimeDependencyEdge> directCallers(String service) {
        return directCallers(TenantIds.DEFAULT, service);
    }

    public List<RuntimeDependencyEdge> directCallers(
            String tenantId,
            String service
    ) {
        return store.incoming(
                TenantIds.normalize(tenantId),
                requireService(service)
        );
    }

    public List<RuntimeDependencyEdge> allEdges() {
        return allEdges(TenantIds.DEFAULT);
    }

    public List<RuntimeDependencyEdge> allEdges(String tenantId) {
        return store.all(TenantIds.normalize(tenantId));
    }

    private String requireService(String service) {
        String normalized = normalizeService(service);
        if (normalized == null) {
            throw new IllegalArgumentException(
                    "Service name is required"
            );
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
        return endpoint == null || endpoint.isBlank()
                ? "*"
                : endpoint.trim();
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

    private record ServiceAtDepth(
            String service,
            int depth
    ) {}
}
