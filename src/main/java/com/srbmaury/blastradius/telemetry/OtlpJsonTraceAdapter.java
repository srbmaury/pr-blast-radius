package com.srbmaury.blastradius.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class OtlpJsonTraceAdapter {

    private static final BigInteger NANOS_PER_SECOND =
            BigInteger.valueOf(1_000_000_000L);

    public List<OpenTelemetrySpanObservation> extract(JsonNode payload) {
        List<OpenTelemetrySpanObservation> observations = new ArrayList<>();

        if (payload == null || payload.isNull()) {
            return observations;
        }

        for (JsonNode resourceSpans : payload.path("resourceSpans")) {
            String sourceService = findAttribute(
                    resourceSpans.path("resource").path("attributes"),
                    "service.name"
            );

            if (sourceService == null || sourceService.isBlank()) {
                continue;
            }

            for (JsonNode scopeSpans : resourceSpans.path("scopeSpans")) {
                for (JsonNode span : scopeSpans.path("spans")) {
                    String spanKind = normalizeSpanKind(span.path("kind"));
                    if (spanKind == null) {
                        continue;
                    }

                    String targetService = findAttribute(
                            span.path("attributes"),
                            "peer.service"
                    );

                    if (targetService == null || targetService.isBlank()) {
                        continue;
                    }

                    observations.add(new OpenTelemetrySpanObservation(
                            sourceService,
                            targetService,
                            spanKind,
                            parseUnixNano(span.path("startTimeUnixNano"))
                    ));
                }
            }
        }

        return observations;
    }

    private String findAttribute(JsonNode attributes, String key) {
        if (attributes == null || !attributes.isArray()) {
            return null;
        }

        for (JsonNode attribute : attributes) {
            if (!key.equals(attribute.path("key").asText())) {
                continue;
            }

            JsonNode value = attribute.path("value");

            if (value.hasNonNull("stringValue")) {
                return value.path("stringValue").asText();
            }
        }

        return null;
    }

    private String normalizeSpanKind(JsonNode kindNode) {
        if (kindNode == null || kindNode.isMissingNode() || kindNode.isNull()) {
            return null;
        }

        if (kindNode.isInt() || kindNode.isLong()) {
            return switch (kindNode.asInt()) {
                case 3 -> "CLIENT";
                case 4 -> "PRODUCER";
                default -> null;
            };
        }

        String value = kindNode.asText();

        return switch (value) {
            case "SPAN_KIND_CLIENT", "CLIENT", "3" -> "CLIENT";
            case "SPAN_KIND_PRODUCER", "PRODUCER", "4" -> "PRODUCER";
            default -> null;
        };
    }

    private Instant parseUnixNano(JsonNode valueNode) {
        if (valueNode == null
                || valueNode.isMissingNode()
                || valueNode.isNull()
                || valueNode.asText().isBlank()) {
            return Instant.now();
        }

        try {
            BigInteger nanos = new BigInteger(valueNode.asText());
            BigInteger[] parts = nanos.divideAndRemainder(NANOS_PER_SECOND);

            return Instant.ofEpochSecond(
                    parts[0].longValueExact(),
                    parts[1].longValueExact()
            );
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }
}
