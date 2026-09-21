package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.domain.RuntimeDependencyGraph;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
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

    public TelemetryController(RuntimeDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @PostMapping("/spans")
    public Map<String, Long> ingest(
            @RequestBody List<OpenTelemetrySpanObservation> observations
    ) {
        long accepted = dependencyService.ingest(observations);
        return Map.of("accepted", accepted);
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
