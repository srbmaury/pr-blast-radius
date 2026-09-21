package com.srbmaury.blastradius.domain;

import java.util.List;

public record OtlpTraceBatch(
        List<OpenTelemetrySpanObservation> dependencyObservations,
        List<TraceSpanObservation> traceSpans
) {}
