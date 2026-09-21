package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
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

    private final JdbcTemplate jdbcTemplate;

    public RuntimeDependencyStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS runtime_dependency_route_edge (
                    source_service VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255) NOT NULL,
                    endpoint VARCHAR(1024) NOT NULL,
                    call_count BIGINT NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    PRIMARY KEY (source_service, target_service, endpoint)
                )
                """);

        migrateLegacyServiceEdges();
    }

    private void migrateLegacyServiceEdges() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO runtime_dependency_route_edge (
                        source_service,
                        target_service,
                        endpoint,
                        call_count,
                        last_seen
                    )
                    SELECT
                        legacy.source_service,
                        legacy.target_service,
                        '*',
                        legacy.call_count,
                        legacy.last_seen
                    FROM runtime_dependency_edge legacy
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM runtime_dependency_route_edge route_edge
                        WHERE route_edge.source_service = legacy.source_service
                          AND route_edge.target_service = legacy.target_service
                          AND route_edge.endpoint = '*'
                    )
                    """);

            jdbcTemplate.execute("DROP TABLE runtime_dependency_edge");
        } catch (DataAccessException ignored) {
            // Fresh installations do not have the legacy table.
        }
    }

    public void record(
            String sourceService,
            String targetService,
            Instant observedAt
    ) {
        record(sourceService, targetService, WILDCARD_ENDPOINT, observedAt);
    }

    public synchronized void record(
            String sourceService,
            String targetService,
            String endpoint,
            Instant observedAt
    ) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);

        List<RuntimeDependencyEdge> existing = jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM runtime_dependency_route_edge
                WHERE source_service = ?
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
                sourceService,
                targetService,
                normalizedEndpoint
        );

        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO runtime_dependency_route_edge (
                        source_service,
                        target_service,
                        endpoint,
                        call_count,
                        last_seen
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
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
                UPDATE runtime_dependency_route_edge
                SET call_count = ?, last_seen = ?
                WHERE source_service = ?
                  AND target_service = ?
                  AND endpoint = ?
                """,
                edge.callCount() + 1,
                Timestamp.from(lastSeen),
                sourceService,
                targetService,
                normalizedEndpoint
        );
    }

    public List<RuntimeDependencyEdge> outgoing(String sourceService) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM runtime_dependency_route_edge
                WHERE source_service = ?
                ORDER BY target_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                sourceService
        );
    }

    public List<RuntimeDependencyEdge> incoming(String targetService) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM runtime_dependency_route_edge
                WHERE target_service = ?
                ORDER BY source_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                targetService
        );
    }

    public List<RuntimeDependencyEdge> all() {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, endpoint, call_count, last_seen
                FROM runtime_dependency_route_edge
                ORDER BY source_service, target_service, endpoint
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getString("endpoint"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                )
        );
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM runtime_dependency_route_edge WHERE last_seen < ?",
                Timestamp.from(cutoff)
        );
    }

    public void clear() {
        jdbcTemplate.update("DELETE FROM runtime_dependency_route_edge");
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
