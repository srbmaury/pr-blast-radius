package com.srbmaury.blastradius.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDependencyStoreTest {

    @Test
    void persistsEdgesAcrossStoreInstances() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(url, "sa", "")
        );

        var firstStore = new RuntimeDependencyStore(jdbcTemplate);
        firstStore.initialize();
        firstStore.record(
                "orders-service",
                "payment-service",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        var secondStore = new RuntimeDependencyStore(jdbcTemplate);
        secondStore.initialize();

        assertThat(secondStore.all()).singleElement().satisfies(edge -> {
            assertThat(edge.sourceService()).isEqualTo("orders-service");
            assertThat(edge.targetService()).isEqualTo("payment-service");
            assertThat(edge.callCount()).isEqualTo(1);
        });
    }

    @Test
    void retentionDeletesOnlyStaleEdges() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();

        Instant now = Instant.now();
        store.record(
                "old-service",
                "legacy-service",
                now.minus(48, ChronoUnit.HOURS)
        );
        store.record(
                "orders-service",
                "payment-service",
                now.minus(1, ChronoUnit.HOURS)
        );

        int deleted = store.deleteOlderThan(now.minus(24, ChronoUnit.HOURS));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.all())
                .extracting(edge -> edge.sourceService() + "->" + edge.targetService())
                .containsExactly("orders-service->payment-service");
    }
}
