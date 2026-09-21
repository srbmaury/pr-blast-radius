package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceSpanObservation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSpanRetentionServiceTest {

    @Test
    void expiresTraceLineageUsingShortRetentionWindow() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TraceSpanStore(jdbc);
        store.initialize();
        store.save(new TraceSpanObservation(
                "trace-old",
                "span-old",
                "",
                "orders-service",
                null,
                "HTTP POST /orders",
                "SERVER",
                Instant.now().minus(25, ChronoUnit.HOURS)
        ));

        var retention = new TraceSpanRetentionService(store, 24);

        assertThat(retention.cleanupExpiredSpans()).isEqualTo(1);
        assertThat(store.findByTraceIds(
                java.util.Set.of("trace-old")
        )).isEmpty();
    }
}
