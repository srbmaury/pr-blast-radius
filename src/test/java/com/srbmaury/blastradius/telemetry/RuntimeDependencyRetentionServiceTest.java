package com.srbmaury.blastradius.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDependencyRetentionServiceTest {

    @Test
    void scheduledCleanupUsesConfiguredRetentionWindow() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();
        store.record(
                "stale-service",
                "target-service",
                Instant.now().minus(8, ChronoUnit.DAYS)
        );

        var retentionService = new RuntimeDependencyRetentionService(store, 168);

        assertThat(retentionService.cleanupExpiredEdges()).isEqualTo(1);
        assertThat(store.all()).isEmpty();
    }
}
