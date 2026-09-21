package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import com.srbmaury.blastradius.tenant.TenantIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class RuntimeDependencyStore {

    private static final String WILDCARD_ENDPOINT = "*";
    private static final String TABLE =
            "tenant_runtime_dependency_route_edge";

    private final JdbcTemplate jdbcTemplate;

    public RuntimeDependencyStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tenant_runtime_dependency_route_edge (
                    tenant_id VARCHAR(128) NOT NULL,
                    source_service VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255) NOT NULL,
                    endpoint VARCHAR(1024) NOT NULL,
                    call_count BIGINT NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    PRIMARY KEY (
                        tenant_id,
                        source_service,
                        target_service,
                        endpoint
                    )
                )
                """);

        migrateExistingRouteEdges();
        migrateLegacyServiceEdges();
    }

    private void migrateExistingRouteEdges() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tenant_runtime_dependency_route_edge (
                        tenant_id,
                        source_service,
                        target_service,
                        endpoint,
                        call_count,
                        last_seen
                    )
                    SELECT
                        'default',
                        legacy.source_service,
                        legacy.target_service,
                        legacy.endpoint,
                        legacy.call_count,
                        legacy.last_seen
                    FROM runtime_dependency_route_edge legacy
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM tenant_runtime_dependency_route_edge scoped
                        WHERE scoped.tenant_id = 'default'
                          AND scoped.source_service = legacy.source_service
                          AND scoped.target_service = legacy.target_service
                          AND scoped.endpoint = legacy.endpoint
                    )
                    """);
        } catch (DataAccessException ignored) {
            // Fresh installations do not have the pre-tenant table.
        }
    }

    private void migrateLegacyServiceEdges() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tenant_runtime_dependency_route_edge (
                        tenant_id,
                        source_service,
                        target_service,
                        endpoint,
                        call_count,
                        last_seen
                    )
                    SELECT
                        'default',
                        legacy.source_service,
                        legacy.target_service,
                        '*',
                        legacy.call_count,
                        legacy.last_seen
                    FROM runtime_dependency_edge legacy
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM tenant_runtime_dependency_route_edge scoped
                        WHERE scoped.tenant_id = 'default'
                          AND scoped.source_service = legacy.source_service
                          AND scoped.target_service = legacy.target_service
                          AND scoped.endpoint = '*'
                    )
                    """);
        } catch (DataAccessException ignored) {
            // Fresh installations do not have the oldest legacy table.
        }
    }

    public void record(
            String sourceService,
            String targetService,
            Instant observedAt
    ) {
        record(
                TenantIds.DEFAULT,
                sourceService,
                targetService,
                WILDCARD_ENDPOINT,
                observedAt
        );
    }

    public void record(
            String sourceService,
            String targetService,
            String endpoint,
            Instant observedAt
    ) {
        record(
                TenantIds.DEFAULT,
                sourceService,
                targetService,
                endpoint,
                observedAt
        );
    }

    public synchronized void record(
            String tenantId,
            String sourceService,
            String targetService,
            String endpoint,
            Instant observedAt
    ) {
        String tenant = TenantIds.normalize(tenantId);
        String normalizedEndpoint = normalizeEndpoint(endpoint);

        List<RuntimeDependencyEdge> existing = jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM tenant_runtime_dependency_route_edge
                WHERE tenant_id = ?
                  AND source_service = ?
                  AND target_service = ?
                  AND endpoint = ?
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                tenant,
                sourceService,
                targetService,
                normalizedEndpoint
        );

        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_runtime_dependency_route_edge (
                        tenant_id,
                        source_service,
                        target_service,
                        endpoint,
                        call_count,
                        last_seen
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    tenant,
                    sourceService,
                    targetService,
                    normalizedEndpoint,
                    1L,
                    Timestamp.from(observedAt)
            );
            return;
        }

        RuntimeDependencyEdge edge = existing.getFirst();
        Instant lastSeen = edge.lastSeen().isAfter(observedAt)
                ? edge.lastSeen()
                : observedAt;

        jdbcTemplate.update(
                """
                UPDATE tenant_runtime_dependency_route_edge
                SET call_count = ?, last_seen = ?
                WHERE tenant_id = ?
                  AND source_service = ?
                  AND target_service = ?
                  AND endpoint = ?
                """,
                edge.callCount() + 1,
                Timestamp.from(lastSeen),
                tenant,
                sourceService,
                targetService,
                normalizedEndpoint
        );
    }

    public List<RuntimeDependencyEdge> outgoing(String sourceService) {
        return outgoing(TenantIds.DEFAULT, sourceService);
    }

    public List<RuntimeDependencyEdge> outgoing(
            String tenantId,
            String sourceService
    ) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM tenant_runtime_dependency_route_edge
                WHERE tenant_id = ?
                  AND source_service = ?
                ORDER BY target_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                TenantIds.normalize(tenantId),
                sourceService
        );
    }

    public List<RuntimeDependencyEdge> incoming(String targetService) {
        return incoming(TenantIds.DEFAULT, targetService);
    }

    public List<RuntimeDependencyEdge> incoming(
            String tenantId,
            String targetService
    ) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM tenant_runtime_dependency_route_edge
                WHERE tenant_id = ?
                  AND target_service = ?
                ORDER BY source_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                TenantIds.normalize(tenantId),
                targetService
        );
    }

    public List<RuntimeDependencyEdge> all() {
        return all(TenantIds.DEFAULT);
    }

    public List<RuntimeDependencyEdge> all(String tenantId) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM tenant_runtime_dependency_route_edge
                WHERE tenant_id = ?
                ORDER BY source_service, target_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                TenantIds.normalize(tenantId)
        );
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE last_seen < ?",
                Timestamp.from(cutoff)
        );
    }

    public void clear() {
        jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE tenant_id = ?",
                TenantIds.DEFAULT
        );
    }

    public void clear(String tenantId) {
        jdbcTemplate.update(
                "DELETE FROM " + TABLE + " WHERE tenant_id = ?",
                TenantIds.normalize(tenantId)
        );
    }

    private RuntimeDependencyEdge mapEdge(
            String sourceService,
            String targetService,
            String endpoint,
            long callCount,
            Timestamp lastSeen
    ) {
        return new RuntimeDependencyEdge(
                sourceService,
                targetService,
                endpoint,
                callCount,
                lastSeen.toInstant()
        );
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank()
                ? WILDCARD_ENDPOINT
                : endpoint.trim();
    }
}
