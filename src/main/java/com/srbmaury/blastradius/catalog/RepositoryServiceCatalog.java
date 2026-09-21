package com.srbmaury.blastradius.catalog;

import com.srbmaury.blastradius.domain.RepositoryServiceMapping;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
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
                CREATE TABLE IF NOT EXISTS repository_service_mapping (
                    repository VARCHAR(512) PRIMARY KEY,
                    service VARCHAR(255) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
    }

    public void put(String repository, String service) {
        String normalizedRepository = normalizeRepository(repository);
        String normalizedService = normalizeService(service);
        Instant now = Instant.now();

        int updated = jdbcTemplate.update(
                """
                UPDATE repository_service_mapping
                SET service = ?, updated_at = ?
                WHERE repository = ?
                """,
                normalizedService,
                Timestamp.from(now),
                normalizedRepository
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO repository_service_mapping (
                        repository,
                        service,
                        updated_at
                    ) VALUES (?, ?, ?)
                    """,
                    normalizedRepository,
                    normalizedService,
                    Timestamp.from(now)
            );
        }
    }

    public Optional<RepositoryServiceMapping> find(String repository) {
        String normalizedRepository = normalizeRepository(repository);

        return jdbcTemplate.query(
                """
                SELECT repository, service, updated_at
                FROM repository_service_mapping
                WHERE repository = ?
                """,
                (rs, rowNum) -> new RepositoryServiceMapping(
                        rs.getString("repository"),
                        rs.getString("service"),
                        rs.getTimestamp("updated_at").toInstant()
                ),
                normalizedRepository
        ).stream().findFirst();
    }

    public List<RepositoryServiceMapping> all() {
        return jdbcTemplate.query(
                """
                SELECT repository, service, updated_at
                FROM repository_service_mapping
                ORDER BY repository
                """,
                (rs, rowNum) -> new RepositoryServiceMapping(
                        rs.getString("repository"),
                        rs.getString("service"),
                        rs.getTimestamp("updated_at").toInstant()
                )
        );
    }

    public boolean delete(String repository) {
        return jdbcTemplate.update(
                "DELETE FROM repository_service_mapping WHERE repository = ?",
                normalizeRepository(repository)
        ) > 0;
    }

    private String normalizeRepository(String repository) {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("Repository is required");
        }

        String normalized = repository.trim().toLowerCase();

        if (!normalized.matches("[a-z0-9_.-]+/[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Repository must use owner/name format"
            );
        }

        return normalized;
    }

    private String normalizeService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("Service is required");
        }
        return service.trim();
    }
}
