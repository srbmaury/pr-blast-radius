package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceSpanObservation;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TraceSpanStore {

    private static final int TRACE_QUERY_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;

    public TraceSpanStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS trace_span (
                    trace_id VARCHAR(64) NOT NULL,
                    span_id VARCHAR(32) NOT NULL,
                    parent_span_id VARCHAR(32),
                    service_name VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255),
                    endpoint VARCHAR(1024) NOT NULL,
                    span_kind VARCHAR(32) NOT NULL,
                    observed_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (trace_id, span_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_trace_span_service_kind_time
                ON trace_span (service_name, span_kind, observed_at)
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_trace_span_trace
                ON trace_span (trace_id)
                """);
    }

    public synchronized void save(TraceSpanObservation span) {
        int updated = jdbcTemplate.update(
                """
                UPDATE trace_span
                SET parent_span_id = ?,
                    service_name = ?,
                    target_service = ?,
                    endpoint = ?,
                    span_kind = ?,
                    observed_at = ?
                WHERE trace_id = ? AND span_id = ?
                """,
                normalizeNullable(span.parentSpanId()),
                span.serviceName(),
                normalizeNullable(span.targetService()),
                normalizeEndpoint(span.endpoint()),
                span.spanKind(),
                Timestamp.from(span.observedAt()),
                span.traceId(),
                span.spanId()
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO trace_span (
                        trace_id,
                        span_id,
                        parent_span_id,
                        service_name,
                        target_service,
                        endpoint,
                        span_kind,
                        observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    span.traceId(),
                    span.spanId(),
                    normalizeNullable(span.parentSpanId()),
                    span.serviceName(),
                    normalizeNullable(span.targetService()),
                    normalizeEndpoint(span.endpoint()),
                    span.spanKind(),
                    Timestamp.from(span.observedAt())
            );
        }
    }

    public long saveAll(Collection<TraceSpanObservation> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }

        spans.forEach(this::save);
        return spans.size();
    }

    public List<TraceSpanObservation> findServerSpans(
            String serviceName,
            Set<String> endpoints,
            int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 5000));

        return jdbcTemplate.query(
                """
                SELECT trace_id,
                       span_id,
                       parent_span_id,
                       service_name,
                       target_service,
                       endpoint,
                       span_kind,
                       observed_at
                FROM trace_span
                WHERE service_name = ?
                  AND span_kind = 'SERVER'
                ORDER BY observed_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> mapSpan(
                        rs.getString("trace_id"),
                        rs.getString("span_id"),
                        rs.getString("parent_span_id"),
                        rs.getString("service_name"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getString("span_kind"),
                        rs.getTimestamp("observed_at")
                ),
                serviceName,
                boundedLimit
        ).stream()
                .filter(span -> endpoints == null
                        || endpoints.isEmpty()
                        || endpoints.contains(span.endpoint()))
                .toList();
    }

    public List<TraceSpanObservation> findByTraceIds(Set<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return List.of();
        }

        List<String> ordered = traceIds.stream()
                .sorted()
                .toList();

        List<TraceSpanObservation> result = new ArrayList<>();

        for (int start = 0; start < ordered.size(); start += TRACE_QUERY_BATCH_SIZE) {
            int end = Math.min(start + TRACE_QUERY_BATCH_SIZE, ordered.size());
            List<String> batch = ordered.subList(start, end);
            String placeholders = batch.stream()
                    .map(ignored -> "?")
                    .collect(Collectors.joining(","));

            result.addAll(jdbcTemplate.query(
                    """
                    SELECT trace_id,
                           span_id,
                           parent_span_id,
                           service_name,
                           target_service,
                           endpoint,
                           span_kind,
                           observed_at
                    FROM trace_span
                    WHERE trace_id IN (%s)
                    ORDER BY trace_id, observed_at
                    """.formatted(placeholders),
                    (rs, rowNum) -> mapSpan(
                            rs.getString("trace_id"),
                            rs.getString("span_id"),
                            rs.getString("parent_span_id"),
                            rs.getString("service_name"),
                            rs.getString("target_service"),
                            rs.getString("endpoint"),
                            rs.getString("span_kind"),
                            rs.getTimestamp("observed_at")
                    ),
                    batch.toArray()
            ));
        }

        return List.copyOf(result);
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM trace_span WHERE observed_at < ?",
                Timestamp.from(cutoff)
        );
    }

    public void clear() {
        jdbcTemplate.update("DELETE FROM trace_span");
    }

    private TraceSpanObservation mapSpan(
            String traceId,
            String spanId,
            String parentSpanId,
            String serviceName,
            String targetService,
            String endpoint,
            String spanKind,
            Timestamp observedAt
    ) {
        return new TraceSpanObservation(
                traceId,
                spanId,
                parentSpanId,
                serviceName,
                targetService,
                endpoint,
                spanKind,
                observedAt.toInstant()
        );
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "*" : endpoint.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
