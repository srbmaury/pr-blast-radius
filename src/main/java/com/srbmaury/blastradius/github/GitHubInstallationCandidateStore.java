package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.tenant.TenantIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class GitHubInstallationCandidateStore {

    private final JdbcTemplate jdbcTemplate;

    public GitHubInstallationCandidateStore(
            @Qualifier("metadataJdbcTemplate")
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS github_installation_candidate (
                    tenant_id VARCHAR(128) NOT NULL,
                    installation_id BIGINT NOT NULL,
                    account_login VARCHAR(255) NOT NULL,
                    account_type VARCHAR(64) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (tenant_id, installation_id)
                )
                """);
    }

    public void replace(
            String tenantId,
            List<GitHubInstallationInfo> candidates,
            Instant expiresAt
    ) {
        String tenant = TenantIds.normalize(tenantId);

        jdbcTemplate.update(
                "DELETE FROM github_installation_candidate WHERE tenant_id = ?",
                tenant
        );

        for (GitHubInstallationInfo candidate : candidates) {
            jdbcTemplate.update(
                    """
                    INSERT INTO github_installation_candidate (
                        tenant_id,
                        installation_id,
                        account_login,
                        account_type,
                        expires_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    tenant,
                    candidate.installationId(),
                    candidate.accountLogin(),
                    candidate.accountType(),
                    Timestamp.from(expiresAt)
            );
        }
    }

    public List<GitHubInstallationInfo> allValid(
            String tenantId,
            Instant now
    ) {
        return jdbcTemplate.query(
                """
                SELECT installation_id,
                       account_login,
                       account_type
                FROM github_installation_candidate
                WHERE tenant_id = ?
                  AND expires_at >= ?
                ORDER BY account_login, installation_id
                """,
                (rs, rowNum) -> new GitHubInstallationInfo(
                        rs.getLong("installation_id"),
                        rs.getString("account_login"),
                        rs.getString("account_type"),
                        "CANDIDATE"
                ),
                TenantIds.normalize(tenantId),
                Timestamp.from(now)
        );
    }

    public boolean isValid(
            String tenantId,
            long installationId,
            Instant now
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM github_installation_candidate
                WHERE tenant_id = ?
                  AND installation_id = ?
                  AND expires_at >= ?
                """,
                Integer.class,
                TenantIds.normalize(tenantId),
                installationId,
                Timestamp.from(now)
        );

        return count != null && count > 0;
    }

    public void deleteForTenant(String tenantId) {
        jdbcTemplate.update(
                "DELETE FROM github_installation_candidate WHERE tenant_id = ?",
                TenantIds.normalize(tenantId)
        );
    }
}
