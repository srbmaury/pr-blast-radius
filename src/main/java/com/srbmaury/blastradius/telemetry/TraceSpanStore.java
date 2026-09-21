package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceSpanObservation;
import com.srbmaury.blastradius.tenant.TenantIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
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
    private static final String TABLE = "tenant_trace_span";

    private final JdbcTemplate jdbcTemplate;

    public TraceSpanStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tenant_trace_span (
                    tenant_id VARCHAR(128) NOT NULL,
                    trace_id VARCHAR(64) NOT NULL,
                    span_id VARCHAR(32) NOT NULL,
                    parent_span_id VARCHAR(32),
                    service_name VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255),
                    endpoint VARCHAR(1024) NOT NULL,
                    span_kind VARCHAR(32) NOT NULL,
                    observed_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (tenant_id, trace_id, span_id)
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_tenant_trace_span_service_kind_time
                ON tenant_trace_span (
                    tenant_id,
                    service_name,
                    span_kind,
                    observed_at
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_tenant_trace_span_trace
                ON tenant_trace_span (tenant_id, trace_id)
                """);

        migrateLegacyTraceSpans();
    }

    private void migrateLegacyTraceSpans() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tenant_trace_span (
                        tenant_id,
                        trace_id,
                        span_id,
                        parent_span_id,
                        service_name,
                        target_service,
                        endpoint,
                        span_kind,
                        observed_at
                    )
                    SELECT
                        'default',
                        legacy.trace_id,
                        legacy.span_id,
                        legacy.parent_span_id,
                        legacy.service_name,
                        legacy.target_service,
                        legacy.endpoint,
                        legacy.span_kind,
                        legacy.observed_at
                    FROM trace_span legacy
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM tenant_trace_span scoped
                        WHERE scoped.tenant_id = 'default'
                          AND scoped.trace_id = legacy.trace_id
                          AND scoped.span_id = legacy.span_id
                    )
                    """);
        } catch (DataAccessException ignored) {
            // Fresh installations do not have the pre-tenant table.
        }
    }

    public synchronized void save(TraceSpanObservation span) {
        save(TenantIds.DEFAULT, span);
    }

    public synchronized void save(
            String tenantId,
            TraceSpanObservation span
    ) {
        String tenant = TenantIds.normalize(tenantId);

        int updated = jdbcTemplate.update(
                """
                UPDATE tenant_trace_span
                SET parent_span_id = ?,
                    service_name = ?,
                    target_service = ?,
                    endpoint = ?,
                    span_kind = ?,
                    observed_at = ?
                WHERE tenant_id = ?
                  AND trace_id = ?
                  AND span_id = ?
                """,
                normalizeNullable(span.parentSpanId()),
                span.serviceName(),
                normalizeNullable(span.targetService()),
                normalizeEndpoint(span.endpoint()),
                span.spanKind(),
                Timestamp.from(span.observedAt()),
                tenant,
                span.traceId(),
                span.spanId()
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_trace_span (
                        tenant_id,
                        trace_id,
                        span_id,
                        parent_span_id,
                        service_name,
                        target_service,
                        endpoint,
                        span_kind,
                        observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenant,
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
        return saveAll(TenantIds.DEFAULT, spans);
    }

    public long saveAll(
            String tenantId,
            Collection<TraceSpanObservation> spans
    ) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }

        spans.forEach(span -> save(tenantId, span));
        return spans.size();
    }

    public List<TraceSpanObservation> findServerSpans(
            String serviceName,
            Set<String> endpoints,
            int limit
    ) {
        return findServerSpans(
                TenantIds.DEFAULT,
                serviceName,
                endpoints,
                limit
        );
    }

    public List<TraceSpanObservation> findServerSpans(
            String tenantId,
            String serviceName,
            Set<String> endpoints,
            int limit
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 5000));
        Set<String> normalizedEndpoints = endpoints == null
                ? Set.of()
                : endpoints.stream()
                        .filter(endpoint ->
                                endpoint != null && !endpoint.isBlank())
                        .map(String::trim)
                        .collect(Collectors.toUnmodifiableSet());

        String endpointPredicate = normalizedEndpoints.isEmpty()
                ? ""
                : " AND endpoint IN ("
                        + normalizedEndpoints.stream()
                                .map(ignored -> "?")
                                .collect(Collectors.joining(","))
                        + ")";

        String sql = """
                SELECT trace_id,
                       span_id,
                       parent_span_id,
                       service_name,
                       target_service,
                       endpoint,
                       span_kind,
                       observed_at
                FROM tenant_trace_span
                WHERE tenant_id = ?
                  AND service_name = ?
                  AND span_kind = 'SERVER'
                """
                + endpointPredicate
                + "
ORDER BY observed_at DESC
LIMIT ?";

        List<Object> args = new ArrayList<>();
        args.add(TenantIds.normalize(tenantId));
        args.add(serviceName);
        args.addAll(
                normalizedEndpoints.stream().sorted().toList()
        );
        args.add(boundedLimit);

        return jdbcTemplate.query(
                sql,
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
                args.toArray()
        );
    }

    public List<TraceSpanObservation> findByTraceIds(
            Set<String> traceIds
    ) {
        return findByTraceIds(TenantIds.DEFAULT, traceIds);
    }

    public List<TraceSpanObservation> findByTraceIds(
            String tenantId,
            Set<String> traceIds
    ) {
        if (traceIds == null || traceIds.isEmpty()) {
            return List.of();
        }

        List<String> ordered = traceIds.stream()
                .sorted()
                .toList();

        List<TraceSpanObservation> result = new ArrayList<>();

        for (int start = 0;
             start < ordered.size();
             start += TRACE_QUERY_BATCH_SIZE) {
            int end = Math.min(
                    start + TRACE_QUERY_BATCH_SIZE,
                    ordered.size()
            );
            List<String> batch = ordered.subList(start, end);
            String placeholders = batch.stream()
                    .map(ignored -> "?")
                    .collect(Collectors.joining(","));

            List<Object> args = new ArrayList<>();
            args.add(TenantIds.normalize(tenantId));
            args.addAll(batch);

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
                    FROM tenant_trace_span
                    WHERE tenant_id = ?
                      AND trace_id IN (%s)
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
                    args.toArray()
            ));
        }

        return List.copyOf(result);
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE observed_at < ?",
                Timestamp.from(cutoff)
        );
    }

    public void clear() {
        clear(TenantIds.DEFAULT);
    }

    public void clear(String tenantId) {
        jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE tenant_id = ?",
                TenantIds.normalize(tenantId)
        );
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
        return endpoint == null || endpoint.isBlank()
                ? "*"
                : endpoint.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
