package com.srbmaury.blastradius.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import com.srbmaury.blastradius.domain.OtlpTraceBatch;
import com.srbmaury.blastradius.domain.TraceSpanObservation;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class OtlpJsonTraceAdapter {

    private static final BigInteger NANOS_PER_SECOND =
            BigInteger.valueOf(1_000_000_000L);

    public List<OpenTelemetrySpanObservation> extract(JsonNode payload) {
        return extractBatch(payload).dependencyObservations();
    }

    public OtlpTraceBatch extractBatch(JsonNode payload) {
        List<OpenTelemetrySpanObservation> dependencies = new ArrayList<>();
        List<TraceSpanObservation> traceSpans = new ArrayList<>();

        if (payload == null || payload.isNull()) {
            return new OtlpTraceBatch(
                    List.of(),
                    List.of()
            );
        }

        for (JsonNode resourceSpans : payload.path("resourceSpans")) {
            String serviceName = findAttribute(
                    resourceSpans.path("resource").path("attributes"),
                    "service.name"
            );

            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }

            for (JsonNode scopeSpans : resourceSpans.path("scopeSpans")) {
                for (JsonNode span : scopeSpans.path("spans")) {
                    String spanKind = normalizeSpanKind(span.path("kind"));
                    if (spanKind == null) {
                        continue;
                    }

                    JsonNode attributes = span.path("attributes");
                    String targetService = resolveTargetService(
                            attributes,
                            spanKind
                    );
                    String endpoint = resolveEndpoint(
                            attributes,
                            spanKind
                    );
                    Instant observedAt = parseUnixNano(
                            span.path("startTimeUnixNano")
                    );

                    if (isOutboundKind(spanKind)
                            && targetService != null
                            && !targetService.isBlank()) {
                        dependencies.add(new OpenTelemetrySpanObservation(
                                serviceName,
                                targetService,
                                endpoint,
                                spanKind,
                                observedAt
                        ));
                    }

                    String traceId = span.path("traceId").asText();
                    String spanId = span.path("spanId").asText();

                    if (traceId.isBlank() || spanId.isBlank()) {
                        continue;
                    }

                    traceSpans.add(new TraceSpanObservation(
                            traceId,
                            spanId,
                            span.path("parentSpanId").asText(),
                            serviceName,
                            targetService,
                            endpoint,
                            spanKind,
                            observedAt
                    ));
                }
            }
        }

        return new OtlpTraceBatch(
                List.copyOf(dependencies),
                List.copyOf(traceSpans)
        );
    }

    private String resolveTargetService(
            JsonNode attributes,
            String spanKind
    ) {
        if (!isOutboundKind(spanKind)) {
            return null;
        }

        return firstAttribute(
                attributes,
                "peer.service",
                "server.address"
        );
    }

    private String resolveEndpoint(
            JsonNode attributes,
            String spanKind
    ) {
        String httpMethod = firstAttribute(
                attributes,
                "http.request.method",
                "http.method"
        );

        String httpRoute = "SERVER".equals(spanKind)
                ? firstAttribute(
                        attributes,
                        "http.route",
                        "url.template"
                )
                : firstAttribute(
                        attributes,
                        "url.template",
                        "http.route"
                );

        if (httpMethod != null && httpRoute != null) {
            return "HTTP "
                    + httpMethod.toUpperCase(Locale.ROOT)
                    + " "
                    + normalizeRoute(httpRoute);
        }

        String rpcService = findAttribute(attributes, "rpc.service");
        String rpcMethod = findAttribute(attributes, "rpc.method");
        String rpcSystem = findAttribute(attributes, "rpc.system");

        if (rpcService != null && rpcMethod != null) {
            return "RPC "
                    + (rpcSystem == null ? "unknown" : rpcSystem)
                    + " "
                    + rpcService
                    + "/"
                    + rpcMethod;
        }

        if ("PRODUCER".equals(spanKind)
                || "CONSUMER".equals(spanKind)) {
            String destination = firstAttribute(
                    attributes,
                    "messaging.destination.name",
                    "messaging.destination"
            );

            if (destination != null) {
                return "MESSAGING " + destination;
            }
        }

        return "*";
    }

    private boolean isOutboundKind(String spanKind) {
        return "CLIENT".equals(spanKind)
                || "PRODUCER".equals(spanKind);
    }

    private String firstAttribute(JsonNode attributes, String... keys) {
        for (String key : keys) {
            String value = findAttribute(attributes, key);

            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
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
        if (kindNode == null
                || kindNode.isMissingNode()
                || kindNode.isNull()) {
            return null;
        }

        if (kindNode.isInt() || kindNode.isLong()) {
            return switch (kindNode.asInt()) {
                case 1 -> "INTERNAL";
                case 2 -> "SERVER";
                case 3 -> "CLIENT";
                case 4 -> "PRODUCER";
                case 5 -> "CONSUMER";
                default -> null;
            };
        }

        String value = kindNode.asText();

        return switch (value) {
            case "SPAN_KIND_INTERNAL", "INTERNAL", "1" -> "INTERNAL";
            case "SPAN_KIND_SERVER", "SERVER", "2" -> "SERVER";
            case "SPAN_KIND_CLIENT", "CLIENT", "3" -> "CLIENT";
            case "SPAN_KIND_PRODUCER", "PRODUCER", "4" -> "PRODUCER";
            case "SPAN_KIND_CONSUMER", "CONSUMER", "5" -> "CONSUMER";
            default -> null;
        };
    }

    private String normalizeRoute(String route) {
        String normalized = route == null ? "/" : route.trim();

        if (normalized.isBlank()) {
            return "/";
        }

        normalized = normalized.startsWith("/")
                ? normalized
                : "/" + normalized;

        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
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
