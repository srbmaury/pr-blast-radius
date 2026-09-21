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
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final RuntimeDependencyService dependencyService;
    private final TraceCausalityService traceCausalityService;
    private final OtlpJsonTraceAdapter otlpJsonTraceAdapter;
    private final TenantAccessResolver tenantAccessResolver;

    public TelemetryController(
            RuntimeDependencyService dependencyService,
            TraceCausalityService traceCausalityService,
            OtlpJsonTraceAdapter otlpJsonTraceAdapter,
            TenantAccessResolver tenantAccessResolver
    ) {
        this.dependencyService = dependencyService;
        this.traceCausalityService = traceCausalityService;
        this.otlpJsonTraceAdapter = otlpJsonTraceAdapter;
        this.tenantAccessResolver = tenantAccessResolver;
    }

    @PostMapping("/spans")
    public Map<String, Long> ingest(
            @RequestBody List<OpenTelemetrySpanObservation> observations,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = tenantAccessResolver.resolveIngestTenant(
                authorization,
                tenantId
        );

        long accepted = dependencyService.ingest(
                tenant,
                observations
        );

        return Map.of("accepted", accepted);
    }

    @PostMapping(
            path = "/otlp-json/v1/traces",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Long> ingestOtlpJson(
            @RequestBody JsonNode payload,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = tenantAccessResolver.resolveIngestTenant(
                authorization,
                tenantId
        );

        OtlpTraceBatch batch =
                otlpJsonTraceAdapter.extractBatch(payload);

        long dependencyAccepted = dependencyService.ingest(
                tenant,
                batch.dependencyObservations()
        );
        long traceSpansAccepted = traceCausalityService.ingest(
                tenant,
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
    public List<RuntimeDependencyEdge> allDependencies(
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return dependencyService.allEdges(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                )
        );
    }

    @GetMapping("/blast-radius")
    public RuntimeBlastRadius blastRadius(
            @RequestParam String service,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestParam(required = false) Set<String> endpoint,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return dependencyService.blastRadius(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                ),
                service,
                maxDepth,
                endpoint == null ? Set.of() : endpoint
        );
    }

    @GetMapping("/trace-causality")
    public TraceCausalityResult traceCausality(
            @RequestParam String service,
            @RequestParam Set<String> endpoint,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return traceCausalityService.analyze(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                ),
                service,
                endpoint
        );
    }

    @GetMapping("/downstream")
    public RuntimeDependencyGraph downstream(
            @RequestParam String service,
            @RequestParam(defaultValue = "3") int maxDepth,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return dependencyService.downstream(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                ),
                service,
                maxDepth
        );
    }
}
