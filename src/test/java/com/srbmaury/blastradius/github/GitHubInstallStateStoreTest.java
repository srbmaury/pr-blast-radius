package com.srbmaury.blastradius.github;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubInstallStateStoreTest {

    @Test
    void stateCanBeConsumedOnlyOnce() {
        var store = store();
        Instant now = Instant.now();

        store.put(
                "hash-a",
                "tenant-a",
                now.plusSeconds(300)
        );

        assertThat(store.consume(
                "hash-a",
                now
        )).contains("tenant-a");

        assertThat(store.consume(
                "hash-a",
                now.plusSeconds(1)
        )).isEmpty();
    }

    @Test
    void expiredStateCannotBeConsumed() {
        var store = store();
        Instant now = Instant.now();

        store.put(
                "hash-b",
                "tenant-a",
                now.minusSeconds(1)
        );

        assertThat(store.consume(
                "hash-b",
                now
        )).isEmpty();
    }

    private GitHubInstallStateStore store() {
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
                new GitHubInstallStateStore(jdbc);
        store.initialize();
        return store;
    }
}
