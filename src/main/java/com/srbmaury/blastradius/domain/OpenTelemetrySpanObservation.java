package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record OpenTelemetrySpanObservation(
        String sourceService,
        String targetService,
        String spanKind,
        Instant observedAt
) {}
