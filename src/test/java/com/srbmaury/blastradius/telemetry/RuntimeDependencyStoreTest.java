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

    @Test
    void keepsDifferentEndpointsAsSeparateEdges() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();

        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");
        store.record(
                "checkout-service",
                "orders-service",
                "HTTP POST /orders",
                observedAt
        );
        store.record(
                "checkout-service",
                "orders-service",
                "HTTP GET /orders/{id}",
                observedAt
        );
        store.record(
                "checkout-service",
                "orders-service",
                "HTTP POST /orders",
                observedAt.plusSeconds(60)
        );

        assertThat(store.incoming("orders-service"))
                .hasSize(2)
                .anySatisfy(edge -> {
                    assertThat(edge.endpoint()).isEqualTo("HTTP POST /orders");
                    assertThat(edge.callCount()).isEqualTo(2);
                })
                .anySatisfy(edge -> {
                    assertThat(edge.endpoint()).isEqualTo("HTTP GET /orders/{id}");
                    assertThat(edge.callCount()).isEqualTo(1);
                });
    }

    @Test
    void migratesLegacyServiceOnlyEdgesAsWildcardEndpoint() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        jdbcTemplate.execute("""
                CREATE TABLE runtime_dependency_edge (
                    source_service VARCHAR(255) NOT NULL,
                    target_service VARCHAR(255) NOT NULL,
                    call_count BIGINT NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    PRIMARY KEY (source_service, target_service)
                )
                """);
        jdbcTemplate.update(
                """
                INSERT INTO runtime_dependency_edge (
                    source_service,
                    target_service,
                    call_count,
                    last_seen
                ) VALUES (?, ?, ?, ?)
                """,
                "checkout-service",
                "orders-service",
                42L,
                java.sql.Timestamp.from(Instant.parse("2026-09-21T10:00:00Z"))
        );

        var store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();

        assertThat(store.all()).singleElement().satisfies(edge -> {
            assertThat(edge.endpoint()).isEqualTo("*");
            assertThat(edge.callCount()).isEqualTo(42);
        });
    }


    @Test
    void isolatesIdenticalServiceNamesAcrossTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();

        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        store.record(
                "tenant-a",
                "orders-service",
                "payment-service",
                "HTTP POST /payments",
                observedAt
        );
        store.record(
                "tenant-b",
                "orders-service",
                "inventory-service",
                "HTTP GET /inventory/{id}",
                observedAt
        );

        assertThat(store.outgoing(
                "tenant-a",
                "orders-service"
        )).singleElement().satisfies(edge ->
                assertThat(edge.targetService())
                        .isEqualTo("payment-service")
        );

        assertThat(store.outgoing(
                "tenant-b",
                "orders-service"
        )).singleElement().satisfies(edge ->
                assertThat(edge.targetService())
                        .isEqualTo("inventory-service")
        );
    }

}
