package com.srbmaury.blastradius.github;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubInstallationStoreTest {

    @Test
    void installationCannotBeReboundAcrossTenants() {
        var store = store();

        store.bind(
                "tenant-a",
                1001L,
                "acme",
                "Organization"
        );

        assertThat(store.findTenant(1001L))
                .contains("tenant-a");

        assertThatThrownBy(() -> store.bind(
                "tenant-b",
                1001L,
                "acme",
                "Organization"
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inactiveInstallationIsNotResolvedUntilReactivated() {
        var store = store();

        store.bind(
                "tenant-a",
                1002L,
                "acme",
                "Organization"
        );

        store.markInactive(1002L);
        assertThat(store.findTenant(1002L))
                .isEmpty();

        store.markActive(1002L);
        assertThat(store.findTenant(1002L))
                .contains("tenant-a");
    }

    private GitHubInstallationStore store() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store =
                new GitHubInstallationStore(jdbc);
        store.initialize();
        return store;
    }

    @Test
    void inactiveInstallationStillCannotMoveToAnotherTenant() {
        var store = store();

        store.bind(
                "tenant-a",
                1003L,
                "acme",
                "Organization"
        );
        store.markInactive(1003L);

        assertThatThrownBy(() -> store.bind(
                "tenant-b",
                1003L,
                "acme",
                "Organization"
        )).isInstanceOf(IllegalStateException.class);
    }


}
