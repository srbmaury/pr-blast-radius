package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record TraceSpanObservation(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String targetService,
        String endpoint,
        String spanKind,
        Instant observedAt
) {}
