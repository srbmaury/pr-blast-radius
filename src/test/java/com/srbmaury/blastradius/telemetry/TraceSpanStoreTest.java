package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceSpanObservation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSpanStoreTest {

    @Test
    void persistsAndReloadsTraceLineageWithoutDuplicateSpans() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TraceSpanStore(jdbc);
        store.initialize();

        var server = span(
                "trace-1",
                "server-1",
                "client-parent",
                "orders-service",
                null,
                "HTTP POST /orders",
                "SERVER",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        store.save(server);
        store.save(server);

        assertThat(store.findServerSpans(
                "orders-service",
                Set.of("HTTP POST /orders"),
                100
        )).singleElement().isEqualTo(server);

        assertThat(store.findByTraceIds(Set.of("trace-1")))
                .containsExactly(server);
    }

    @Test
    void deletesOnlyExpiredTraceSpans() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TraceSpanStore(jdbc);
        store.initialize();

        Instant now = Instant.now();

        store.save(span(
                "old-trace",
                "old-span",
                "",
                "orders-service",
                null,
                "HTTP POST /orders",
                "SERVER",
                now.minus(30, ChronoUnit.HOURS)
        ));
        store.save(span(
                "fresh-trace",
                "fresh-span",
                "",
                "orders-service",
                null,
                "HTTP POST /orders",
                "SERVER",
                now.minus(1, ChronoUnit.HOURS)
        ));

        assertThat(store.deleteOlderThan(
                now.minus(24, ChronoUnit.HOURS)
        )).isEqualTo(1);

        assertThat(store.findByTraceIds(
                Set.of("old-trace", "fresh-trace")
        )).extracting(TraceSpanObservation::traceId)
                .containsExactly("fresh-trace");
    }

    private TraceSpanObservation span(
            String traceId,
            String spanId,
            String parentSpanId,
            String service,
            String target,
            String endpoint,
            String kind,
            Instant observedAt
    ) {
        return new TraceSpanObservation(
                traceId,
                spanId,
                parentSpanId,
                service,
                target,
                endpoint,
                kind,
                observedAt
        );
    }

    @Test
    void isolatesIdenticalTraceIdsAcrossTenants() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TraceSpanStore(jdbc);
        store.initialize();

        var tenantASpan = span(
                "shared-trace",
                "server-1",
                "",
                "orders-service",
                null,
                "HTTP POST /orders",
                "SERVER",
                Instant.parse("2026-09-21T10:00:00Z")
        );

        var tenantBSpan = span(
                "shared-trace",
                "server-1",
                "",
                "orders-service",
                null,
                "HTTP GET /orders/{id}",
                "SERVER",
                Instant.parse("2026-09-21T10:01:00Z")
        );

        store.save("tenant-a", tenantASpan);
        store.save("tenant-b", tenantBSpan);

        assertThat(store.findByTraceIds(
                "tenant-a",
                Set.of("shared-trace")
        )).containsExactly(tenantASpan);

        assertThat(store.findByTraceIds(
                "tenant-b",
                Set.of("shared-trace")
        )).containsExactly(tenantBSpan);
    }

}
