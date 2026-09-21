package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record OpenTelemetrySpanObservation(
        String sourceService,
        String targetService,
        String endpoint,
        String spanKind,
        Instant observedAt
) {
    public OpenTelemetrySpanObservation(
            String sourceService,
            String targetService,
            String spanKind,
            Instant observedAt
    ) {
        this(sourceService, targetService, "*", spanKind, observedAt);
    }
}
