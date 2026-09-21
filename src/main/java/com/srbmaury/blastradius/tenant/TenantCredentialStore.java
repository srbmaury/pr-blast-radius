package com.srbmaury.blastradius.tenant;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
public class TenantCredentialStore {

    private final JdbcTemplate jdbcTemplate;

    public TenantCredentialStore(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tenant_credential (
                    tenant_id VARCHAR(128) PRIMARY KEY,
                    api_token_hash VARCHAR(64) NOT NULL UNIQUE,
                    ingest_token_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
    }

    public synchronized void put(
            String tenantId,
            String apiTokenHash,
            String ingestTokenHash
    ) {
        String tenant = TenantIds.normalize(tenantId);
        Instant now = Instant.now();

        int updated = jdbcTemplate.update(
                """
                UPDATE tenant_credential
                SET api_token_hash = ?,
                    ingest_token_hash = ?,
                    updated_at = ?
                WHERE tenant_id = ?
                """,
                apiTokenHash,
                ingestTokenHash,
                Timestamp.from(now),
                tenant
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_credential (
                        tenant_id,
                        api_token_hash,
                        ingest_token_hash,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    tenant,
                    apiTokenHash,
                    ingestTokenHash,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        }
    }

    public Optional<String> findTenantByApiHash(
            String apiTokenHash
    ) {
        return jdbcTemplate.query(
                """
                SELECT tenant_id
                FROM tenant_credential
                WHERE api_token_hash = ?
                """,
                (rs, rowNum) -> rs.getString("tenant_id"),
                apiTokenHash
        ).stream().findFirst();
    }

    public Optional<String> findTenantByIngestHash(
            String ingestTokenHash
    ) {
        return jdbcTemplate.query(
                """
                SELECT tenant_id
                FROM tenant_credential
                WHERE ingest_token_hash = ?
                """,
                (rs, rowNum) -> rs.getString("tenant_id"),
                ingestTokenHash
        ).stream().findFirst();
    }

    public boolean exists(String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM tenant_credential
                WHERE tenant_id = ?
                """,
                Integer.class,
                TenantIds.normalize(tenantId)
        );

        return count != null && count > 0;
    }
}
