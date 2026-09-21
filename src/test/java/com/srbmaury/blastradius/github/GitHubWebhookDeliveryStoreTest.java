package com.srbmaury.blastradius.github;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookDeliveryStoreTest {

    @Test
    void completedDeliveryStaysDeduplicatedButFailedDeliveryCanRetry() {
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
                new GitHubWebhookDeliveryStore(jdbc);
        store.initialize();

        assertThat(store.claim(
                "delivery-a",
                "pull_request"
        )).isTrue();

        assertThat(store.claim(
                "delivery-a",
                "pull_request"
        )).isFalse();

        store.fail(
                "delivery-a",
                "temporary failure"
        );

        assertThat(store.claim(
                "delivery-a",
                "pull_request"
        )).isTrue();

        store.complete(
                "delivery-a",
                "done"
        );

        assertThat(store.claim(
                "delivery-a",
                "pull_request"
        )).isFalse();
    }
}
