package com.srbmaury.blastradius.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryServiceCatalogTest {

    @Test
    void persistsAndUpdatesRepositoryMapping() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var catalog = new RepositoryServiceCatalog(jdbcTemplate);
        catalog.initialize();

        catalog.put("Acme/Orders", "orders-service");

        assertThat(catalog.find("acme/orders"))
                .get()
                .extracting(mapping -> mapping.service())
                .isEqualTo("orders-service");

        catalog.put("acme/orders", "orders-v2");

        assertThat(catalog.find("ACME/ORDERS"))
                .get()
                .extracting(mapping -> mapping.service())
                .isEqualTo("orders-v2");

        assertThat(catalog.all()).hasSize(1);
    }

    @Test
    void deletesMapping() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var catalog = new RepositoryServiceCatalog(jdbcTemplate);
        catalog.initialize();
        catalog.put("acme/orders", "orders-service");

        assertThat(catalog.delete("acme/orders")).isTrue();
        assertThat(catalog.find("acme/orders")).isEmpty();
    }

    @Test
    void isolatesSameRepositoryAcrossTenants() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var catalog = new RepositoryServiceCatalog(jdbcTemplate);
        catalog.initialize();

        catalog.put(
                "tenant-a",
                "acme/orders",
                "orders-a"
        );
        catalog.put(
                "tenant-b",
                "acme/orders",
                "orders-b"
        );

        assertThat(catalog.find(
                "tenant-a",
                "acme/orders"
        )).get().extracting(mapping -> mapping.service())
                .isEqualTo("orders-a");

        assertThat(catalog.find(
                "tenant-b",
                "acme/orders"
        )).get().extracting(mapping -> mapping.service())
                .isEqualTo("orders-b");
    }

}
