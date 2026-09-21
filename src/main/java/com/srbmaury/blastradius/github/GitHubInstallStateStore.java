package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.tenant.TenantIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
public class GitHubInstallStateStore {

    private final JdbcTemplate jdbcTemplate;

    public GitHubInstallStateStore(
            @Qualifier("metadataJdbcTemplate")
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS github_install_state (
                    state_hash VARCHAR(64) PRIMARY KEY,
                    tenant_id VARCHAR(128) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    consumed_at TIMESTAMP
                )
                """);
    }

    public void put(
            String stateHash,
            String tenantId,
            Instant expiresAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO github_install_state (
                    state_hash,
                    tenant_id,
                    expires_at,
                    consumed_at
                ) VALUES (?, ?, ?, NULL)
                """,
                stateHash,
                TenantIds.normalize(tenantId),
                Timestamp.from(expiresAt)
        );
    }

    public synchronized Optional<String> consume(
            String stateHash,
            Instant now
    ) {
        Optional<String> tenant = jdbcTemplate.query(
                """
                SELECT tenant_id
                FROM github_install_state
                WHERE state_hash = ?
                  AND consumed_at IS NULL
                  AND expires_at >= ?
                """,
                (rs, rowNum) -> rs.getString("tenant_id"),
                stateHash,
                Timestamp.from(now)
        ).stream().findFirst();

        if (tenant.isEmpty()) {
            return Optional.empty();
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE github_install_state
                SET consumed_at = ?
                WHERE state_hash = ?
                  AND consumed_at IS NULL
                """,
                Timestamp.from(now),
                stateHash
        );

        return updated == 1 ? tenant : Optional.empty();
    }

    public int deleteExpired(Instant now) {
        return jdbcTemplate.update(
                "DELETE FROM github_install_state WHERE expires_at < ?",
                Timestamp.from(now)
        );
    }
}
