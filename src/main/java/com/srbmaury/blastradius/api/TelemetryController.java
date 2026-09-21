package com.srbmaury.blastradius.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.telemetry.OtlpJsonTraceAdapter;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final RuntimeDependencyService dependencyService;
    private final OtlpJsonTraceAdapter otlpJsonTraceAdapter;

    public TelemetryController(
            RuntimeDependencyService dependencyService,
            OtlpJsonTraceAdapter otlpJsonTraceAdapter
    ) {
        this.dependencyService = dependencyService;
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
        List<OpenTelemetrySpanObservation> observations =
                otlpJsonTraceAdapter.extract(payload);

        long accepted = dependencyService.ingest(observations);

        return Map.of(
                "extracted",
                (long) observations.size(),
                "accepted",
                accepted
        );
    }

    @GetMapping("/dependencies")
    public List<RuntimeDependencyEdge> allDependencies() {
        return dependencyService.allEdges();
    }

    @GetMapping("/downstream")
    public RuntimeDependencyGraph downstream(
            @RequestParam String service,
            @RequestParam(defaultValue = "3") int maxDepth
    ) {
        return dependencyService.downstream(service, maxDepth);
    }
}
