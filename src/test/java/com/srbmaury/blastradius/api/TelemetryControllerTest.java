package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.telemetry.OtlpJsonTraceAdapter;
import com.srbmaury.blastradius.telemetry.RuntimeDependencyService;
import com.srbmaury.blastradius.telemetry.TraceCausalityService;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryControllerTest {

    @Test
    void ingestUsesIngestCredentialTenant() {
        RuntimeDependencyService dependencies =
                mock(RuntimeDependencyService.class);
        TraceCausalityService traces =
                mock(TraceCausalityService.class);
        OtlpJsonTraceAdapter adapter =
                mock(OtlpJsonTraceAdapter.class);
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);

        var observation = new OpenTelemetrySpanObservation(
                "orders-service",
                "payment-service",
                "HTTP POST /payments",
                "CLIENT",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        when(access.resolveIngestTenant(
                "Bearer ingest-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        when(dependencies.ingest(
                "tenant-a",
                List.of(observation)
        )).thenReturn(1L);

        var controller = new TelemetryController(
                dependencies,
                traces,
                adapter,
                access
        );

        assertThat(controller.ingest(
                List.of(observation),
                "tenant-a",
                "Bearer ingest-token"
        ).get("accepted")).isEqualTo(1L);

        verify(dependencies).ingest(
                "tenant-a",
                List.of(observation)
        );
    }

    @Test
    void telemetryReadsUseApiCredentialTenant() {
        RuntimeDependencyService dependencies =
                mock(RuntimeDependencyService.class);
        TraceCausalityService traces =
                mock(TraceCausalityService.class);
        OtlpJsonTraceAdapter adapter =
                mock(OtlpJsonTraceAdapter.class);
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);

        when(access.resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        when(dependencies.allEdges("tenant-a"))
                .thenReturn(List.of());

        var controller = new TelemetryController(
                dependencies,
                traces,
                adapter,
                access
        );

        assertThat(controller.allDependencies(
                "tenant-a",
                "Bearer api-token"
        )).isEmpty();

        verify(dependencies).allEdges("tenant-a");
    }
}
