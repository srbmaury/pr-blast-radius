package com.srbmaury.blastradius.catalog;

import com.srbmaury.blastradius.domain.RepositoryServiceMapping;
import com.srbmaury.blastradius.tenant.TenantIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class RepositoryServiceCatalog {

    private final JdbcTemplate jdbcTemplate;

    public RepositoryServiceCatalog(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tenant_repository_service_mapping (
                    tenant_id VARCHAR(128) NOT NULL,
                    repository VARCHAR(512) NOT NULL,
                    service VARCHAR(255) NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (tenant_id, repository)
                )
                """);

        migrateLegacyMappings();
    }

    private void migrateLegacyMappings() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tenant_repository_service_mapping (
                        tenant_id,
                        repository,
                        service,
                        updated_at
                    )
                    SELECT
                        'default',
                        legacy.repository,
                        legacy.service,
                        legacy.updated_at
                    FROM repository_service_mapping legacy
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM tenant_repository_service_mapping scoped
                        WHERE scoped.tenant_id = 'default'
                          AND scoped.repository = legacy.repository
                    )
                    """);
        } catch (DataAccessException ignored) {
            // Fresh installations do not have the pre-tenant table.
        }
    }

    public void put(String repository, String service) {
        put(TenantIds.DEFAULT, repository, service);
    }

    public void put(
            String tenantId,
            String repository,
            String service
    ) {
        String tenant = TenantIds.normalize(tenantId);
        String normalizedRepository =
                normalizeRepository(repository);
        String normalizedService = normalizeService(service);
        Instant now = Instant.now();

        int updated = jdbcTemplate.update(
                """
                UPDATE tenant_repository_service_mapping
                SET service = ?, updated_at = ?
                WHERE tenant_id = ?
                  AND repository = ?
                """,
                normalizedService,
                Timestamp.from(now),
                tenant,
                normalizedRepository
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_repository_service_mapping (
                        tenant_id,
                        repository,
                        service,
                        updated_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    tenant,
                    normalizedRepository,
                    normalizedService,
                    Timestamp.from(now)
            );
        }
    }

    public Optional<RepositoryServiceMapping> find(
            String repository
    ) {
        return find(TenantIds.DEFAULT, repository);
    }

    public Optional<RepositoryServiceMapping> find(
            String tenantId,
            String repository
    ) {
        String normalizedRepository =
                normalizeRepository(repository);

        return jdbcTemplate.query(
                """
                SELECT repository, service, updated_at
                FROM tenant_repository_service_mapping
                WHERE tenant_id = ?
                  AND repository = ?
                """,
                (rs, rowNum) -> new RepositoryServiceMapping(
                        rs.getString("repository"),
                        rs.getString("service"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                TenantIds.normalize(tenantId),
                normalizedRepository
        ).stream().findFirst();
    }

    public List<RepositoryServiceMapping> all() {
        return all(TenantIds.DEFAULT);
    }

    public List<RepositoryServiceMapping> all(
            String tenantId
    ) {
        return jdbcTemplate.query(
                """
                SELECT repository, service, updated_at
                FROM tenant_repository_service_mapping
                WHERE tenant_id = ?
                ORDER BY repository
                """,
                (rs, rowNum) -> new RepositoryServiceMapping(
                        rs.getString("repository"),
                        rs.getString("service"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                TenantIds.normalize(tenantId)
        );
    }

    public boolean delete(String repository) {
        return delete(TenantIds.DEFAULT, repository);
    }

    public boolean delete(
            String tenantId,
            String repository
    ) {
        return jdbcTemplate.update(
                """
                DELETE FROM tenant_repository_service_mapping
                WHERE tenant_id = ?
                  AND repository = ?
                """,
                TenantIds.normalize(tenantId),
                normalizeRepository(repository)
        ) > 0;
    }

    private String normalizeRepository(String repository) {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException(
                    "Repository is required"
            );
        }

        String normalized =
                repository.trim().toLowerCase();

        if (!normalized.matches(
                "[a-z0-9_.-]+/[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Repository must use owner/name format"
            );
        }

        return normalized;
    }

    private String normalizeService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException(
                    "Service is required"
            );
        }
        return service.trim();
    }
}
