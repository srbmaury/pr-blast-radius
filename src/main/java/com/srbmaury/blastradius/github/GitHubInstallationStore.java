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
import java.util.Optional;

@Component
public class GitHubInstallationStore {

    private final JdbcTemplate jdbcTemplate;

    public GitHubInstallationStore(
            @Qualifier("metadataJdbcTemplate")
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS github_installation (
                    installation_id BIGINT PRIMARY KEY,
                    tenant_id VARCHAR(128) NOT NULL,
                    account_login VARCHAR(255) NOT NULL,
                    account_type VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_github_installation_tenant
                ON github_installation (tenant_id, status)
                """);
    }

    public synchronized void bind(
            String tenantId,
            long installationId,
            String accountLogin,
            String accountType
    ) {
        String tenant = TenantIds.normalize(tenantId);
        Instant now = Instant.now();

        Optional<String> existingTenant = findTenant(
                installationId
        );

        if (existingTenant.isPresent()
                && !existingTenant.get().equals(tenant)) {
            throw new IllegalStateException(
                    "GitHub installation is already bound to another tenant"
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE github_installation
                SET tenant_id = ?,
                    account_login = ?,
                    account_type = ?,
                    status = 'ACTIVE',
                    updated_at = ?
                WHERE installation_id = ?
                """,
                tenant,
                accountLogin,
                accountType,
                Timestamp.from(now),
                installationId
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO github_installation (
                        installation_id,
                        tenant_id,
                        account_login,
                        account_type,
                        status,
                        updated_at
                    ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                    """,
                    installationId,
                    tenant,
                    accountLogin,
                    accountType,
                    Timestamp.from(now)
            );
        }
    }

    public Optional<String> findTenant(long installationId) {
        return jdbcTemplate.query(
                """
                SELECT tenant_id
                FROM github_installation
                WHERE installation_id = ?
                  AND status = 'ACTIVE'
                """,
                (rs, rowNum) -> rs.getString("tenant_id"),
                installationId
        ).stream().findFirst();
    }

    public Optional<GitHubInstallationInfo> find(
            long installationId
    ) {
        return jdbcTemplate.query(
                """
                SELECT installation_id,
                       account_login,
                       account_type,
                       status
                FROM github_installation
                WHERE installation_id = ?
                """,
                (rs, rowNum) -> new GitHubInstallationInfo(
                        rs.getLong("installation_id"),
                        rs.getString("account_login"),
                        rs.getString("account_type"),
                        rs.getString("status")
                ),
                installationId
        ).stream().findFirst();
    }

    public List<GitHubInstallationInfo> allForTenant(
            String tenantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT installation_id,
                       account_login,
                       account_type,
                       status
                FROM github_installation
                WHERE tenant_id = ?
                ORDER BY account_login, installation_id
                """,
                (rs, rowNum) -> new GitHubInstallationInfo(
                        rs.getLong("installation_id"),
                        rs.getString("account_login"),
                        rs.getString("account_type"),
                        rs.getString("status")
                ),
                TenantIds.normalize(tenantId)
        );
    }

    public void markInactive(long installationId) {
        jdbcTemplate.update(
                """
                UPDATE github_installation
                SET status = 'INACTIVE',
                    updated_at = ?
                WHERE installation_id = ?
                """,
                Timestamp.from(Instant.now()),
                installationId
        );
    }
}
