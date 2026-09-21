package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceCausalEdge;
import com.srbmaury.blastradius.domain.TraceCausalityResult;
import com.srbmaury.blastradius.domain.TraceSpanObservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Service
public class TraceCausalityService {

    private static final Set<String> OUTBOUND_KINDS =
            Set.of("CLIENT", "PRODUCER");

    private final TraceSpanStore store;
    private final int maxRootSpans;

    public TraceCausalityService(
            TraceSpanStore store,
            @Value("${metadata.trace-max-root-spans:1000}") int maxRootSpans
    ) {
        this.store = store;
        this.maxRootSpans = Math.max(1, Math.min(maxRootSpans, 5000));
    }

    public long ingest(List<TraceSpanObservation> spans) {
        return store.saveAll(spans);
    }

    public TraceCausalityResult analyze(
            String rootService,
            Set<String> changedEndpoints
    ) {
        Set<String> normalizedEndpoints = normalizeEndpoints(changedEndpoints);

        if (rootService == null
                || rootService.isBlank()
                || normalizedEndpoints.isEmpty()) {
            return emptyResult(rootService, normalizedEndpoints);
        }

        List<TraceSpanObservation> roots = store.findServerSpans(
                rootService.trim(),
                normalizedEndpoints,
                maxRootSpans
        );

        if (roots.isEmpty()) {
            return emptyResult(rootService.trim(), normalizedEndpoints);
        }

        Set<String> traceIds = roots.stream()
                .map(TraceSpanObservation::traceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, List<TraceSpanObservation>> spansByTrace =
                groupByTrace(store.findByTraceIds(traceIds));

        Map<EdgeKey, EdgeAggregate> aggregates = new LinkedHashMap<>();

        for (TraceSpanObservation root : roots) {
            List<TraceSpanObservation> traceSpans =
                    spansByTrace.getOrDefault(root.traceId(), List.of());

            collectDescendantOutboundEdges(
                    root,
                    traceSpans,
                    aggregates
            );
        }

        List<TraceCausalEdge> edges = aggregates.entrySet()
                .stream()
                .map(entry -> entry.getValue().toEdge(entry.getKey()))
                .sorted(Comparator
                        .comparing(TraceCausalEdge::sourceService)
                        .thenComparing(TraceCausalEdge::targetService)
                        .thenComparing(TraceCausalEdge::endpoint))
                .toList();

        return new TraceCausalityResult(
                rootService.trim(),
                normalizedEndpoints,
                roots.size(),
                traceIds.size(),
                edges
        );
    }

    private void collectDescendantOutboundEdges(
            TraceSpanObservation root,
            List<TraceSpanObservation> traceSpans,
            Map<EdgeKey, EdgeAggregate> aggregates
    ) {
        Map<String, List<TraceSpanObservation>> childrenByParent =
                new HashMap<>();

        for (TraceSpanObservation span : traceSpans) {
            String parent = span.parentSpanId();
            if (parent == null || parent.isBlank()) {
                continue;
            }

            childrenByParent
                    .computeIfAbsent(parent, ignored -> new ArrayList<>())
                    .add(span);
        }

        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        queue.add(root.spanId());
        visited.add(root.spanId());

        while (!queue.isEmpty()) {
            String parentSpanId = queue.remove();

            for (TraceSpanObservation child :
                    childrenByParent.getOrDefault(parentSpanId, List.of())) {
                if (!visited.add(child.spanId())) {
                    continue;
                }

                if (isOutboundDependency(child)) {
                    EdgeKey key = new EdgeKey(
                            child.serviceName(),
                            child.targetService(),
                            normalizeEndpoint(child.endpoint())
                    );

                    aggregates
                            .computeIfAbsent(key, ignored -> new EdgeAggregate())
                            .add(
                                    child.traceId(),
                                    child.spanId(),
                                    root.endpoint(),
                                    child.observedAt()
                            );
                }

                queue.add(child.spanId());
            }
        }
    }

    private boolean isOutboundDependency(TraceSpanObservation span) {
        return OUTBOUND_KINDS.contains(span.spanKind())
                && span.targetService() != null
                && !span.targetService().isBlank()
                && span.serviceName() != null
                && !span.serviceName().isBlank();
    }

    private Map<String, List<TraceSpanObservation>> groupByTrace(
            List<TraceSpanObservation> spans
    ) {
        Map<String, List<TraceSpanObservation>> result = new HashMap<>();

        for (TraceSpanObservation span : spans) {
            result.computeIfAbsent(
                    span.traceId(),
                    ignored -> new ArrayList<>()
            ).add(span);
        }

        return result;
    }

    private Set<String> normalizeEndpoints(Set<String> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return Set.of();
        }

        return endpoints.stream()
                .filter(endpoint -> endpoint != null && !endpoint.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "*" : endpoint.trim();
    }

    private TraceCausalityResult emptyResult(
            String rootService,
            Set<String> endpoints
    ) {
        return new TraceCausalityResult(
                rootService == null ? "" : rootService.trim(),
                endpoints == null ? Set.of() : endpoints,
                0,
                0,
                List.of()
        );
    }

    private record EdgeKey(
            String sourceService,
            String targetService,
            String endpoint
    ) {}

    private static final class EdgeAggregate {
        private final Set<String> spanKeys = new HashSet<>();
        private final Set<String> traceIds = new HashSet<>();
        private final Set<String> rootEndpoints = new LinkedHashSet<>();
        private Instant lastSeen = Instant.EPOCH;

        private void add(
                String traceId,
                String spanId,
                String rootEndpoint,
                Instant observedAt
        ) {
            String spanKey = traceId + ":" + spanId;

            if (!spanKeys.add(spanKey)) {
                rootEndpoints.add(rootEndpoint);
                return;
            }

            traceIds.add(traceId);
            rootEndpoints.add(rootEndpoint);

            if (observedAt != null && observedAt.isAfter(lastSeen)) {
                lastSeen = observedAt;
            }
        }

        private TraceCausalEdge toEdge(EdgeKey key) {
            return new TraceCausalEdge(
                    key.sourceService(),
                    key.targetService(),
                    key.endpoint(),
                    spanKeys.size(),
                    traceIds.size(),
                    lastSeen.equals(Instant.EPOCH) ? null : lastSeen,
                    Set.copyOf(rootEndpoints)
            );
        }
    }
}
