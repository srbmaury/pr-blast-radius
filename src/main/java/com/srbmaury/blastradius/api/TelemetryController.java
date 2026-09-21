package com.srbmaury.blastradius.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.OtlpTraceBatch;
import com.srbmaury.blastradius.domain.RuntimeBlastRadius;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.domain.TraceCausalityResult;
import com.srbmaury.blastradius.telemetry.OtlpJsonTraceAdapter;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final RuntimeDependencyService dependencyService;
    private final TraceCausalityService traceCausalityService;
    private final OtlpJsonTraceAdapter otlpJsonTraceAdapter;

    public TelemetryController(
            RuntimeDependencyService dependencyService,
            TraceCausalityService traceCausalityService,
            OtlpJsonTraceAdapter otlpJsonTraceAdapter
    ) {
        this.dependencyService = dependencyService;
        this.traceCausalityService = traceCausalityService;
        this.otlpJsonTraceAdapter = otlpJsonTraceAdapter;
    }

    @PostMapping("/spans")
    public Map<String, Long> ingest(
            @RequestBody List<OpenTelemetrySpanObservation> observations
    ) {
        long accepted = dependencyService.ingest(observations);
        return Map.of("accepted", accepted);
    }

    @PostMapping(
            path = "/otlp-json/v1/traces",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Long> ingestOtlpJson(@RequestBody JsonNode payload) {
        OtlpTraceBatch batch = otlpJsonTraceAdapter.extractBatch(payload);

        long dependencyAccepted = dependencyService.ingest(
                batch.dependencyObservations()
        );
        long traceSpansAccepted = traceCausalityService.ingest(
                batch.traceSpans()
        );

        return Map.of(
                "dependencyObservations",
                (long) batch.dependencyObservations().size(),
                "dependencyAccepted",
                dependencyAccepted,
                "traceSpans",
                traceSpansAccepted
        );
    }

    @GetMapping("/dependencies")
    public List<RuntimeDependencyEdge> allDependencies() {
        return dependencyService.allEdges();
    }

    @GetMapping("/blast-radius")
    public RuntimeBlastRadius blastRadius(
            @RequestParam String service,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(required = false) Set<String> endpoint
    ) {
        return dependencyService.blastRadius(
                service,
                maxDepth,
                endpoint == null ? Set.of() : endpoint
        );
    }

    @GetMapping("/trace-causality")
    public TraceCausalityResult traceCausality(
            @RequestParam String service,
            @RequestParam Set<String> endpoint
    ) {
        return traceCausalityService.analyze(service, endpoint);
    }

    @GetMapping("/downstream")
    public RuntimeDependencyGraph downstream(
            @RequestParam String service,
            @RequestParam(defaultValue = "3") int maxDepth
    ) {
        return dependencyService.downstream(service, maxDepth);
    }
}
