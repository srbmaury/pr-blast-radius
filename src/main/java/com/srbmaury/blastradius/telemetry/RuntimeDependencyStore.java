package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.RuntimeDependencyEdge;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class RuntimeDependencyStore {

    private final JdbcTemplate jdbcTemplate;

    public RuntimeDependencyStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS runtime_dependency_edge (
                    source_service VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255) NOT NULL,
                    call_count BIGINT NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    PRIMARY KEY (source_service, target_service)
                )
                """);
    }

    public synchronized void record(
            String sourceService,
            String targetService,
            Instant observedAt
    ) {
        List<RuntimeDependencyEdge> existing = jdbcTemplate.query(
                """
                SELECT source_service, target_service, call_count, last_seen
                FROM runtime_dependency_edge
                WHERE source_service = ? AND target_service = ?
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                sourceService,
                targetService
        );

        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO runtime_dependency_edge (
                        source_service,
                        target_service,
                        call_count,
                        last_seen
                    ) VALUES (?, ?, ?, ?)
                    """,
                    sourceService,
                    targetService,
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
                UPDATE runtime_dependency_edge
                SET call_count = ?, last_seen = ?
                WHERE source_service = ? AND target_service = ?
                """,
                edge.callCount() + 1,
                Timestamp.from(lastSeen),
                sourceService,
                targetService
        );
    }

    public List<RuntimeDependencyEdge> outgoing(String sourceService) {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, call_count, last_seen
                FROM runtime_dependency_edge
                WHERE source_service = ?
                ORDER BY target_service
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                ),
                sourceService
        );
    }

    public List<RuntimeDependencyEdge> all() {
        return jdbcTemplate.query(
                """
                SELECT source_service, target_service, call_count, last_seen
                FROM runtime_dependency_edge
                ORDER BY source_service, target_service
                """,
                (rs, rowNum) -> mapEdge(
                        rs.getString("source_service"),
                        rs.getString("target_service"),
                        rs.getLong("call_count"),
                        rs.getTimestamp("last_seen")
                )
        );
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM runtime_dependency_edge WHERE last_seen < ?",
                Timestamp.from(cutoff)
        );
    }

    public void clear() {
        jdbcTemplate.update("DELETE FROM runtime_dependency_edge");
    }

    private RuntimeDependencyEdge mapEdge(
            String sourceService,
            String targetService,
            long callCount,
            Timestamp lastSeen
    ) {
        return new RuntimeDependencyEdge(
                sourceService,
                targetService,
                callCount,
                lastSeen.toInstant()
        );
    }
}
