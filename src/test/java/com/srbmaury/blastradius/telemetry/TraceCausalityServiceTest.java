package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.TraceSpanObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCausalityServiceTest {

    private TraceSpanStore store;
    private TraceCausalityService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        store = new TraceSpanStore(jdbc);
        store.initialize();
        service = new TraceCausalityService(store, 1000);
    }

    @Test
    void reconstructsOnlyDescendantsOfChangedEndpointServerSpan() {
        Instant t = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                span("trace-a", "checkout-client", "", "checkout-service",
                        "orders-service", "HTTP POST /orders", "CLIENT", t),
                span("trace-a", "orders-server", "checkout-client", "orders-service",
                        null, "HTTP POST /orders", "SERVER", t.plusMillis(1)),
                span("trace-a", "orders-internal", "orders-server", "orders-service",
                        null, "*", "INTERNAL", t.plusMillis(2)),
                span("trace-a", "payment-client", "orders-internal", "orders-service",
                        "payment-service", "HTTP POST /payments", "CLIENT", t.plusMillis(3)),
                span("trace-a", "payment-server", "payment-client", "payment-service",
                        null, "HTTP POST /payments", "SERVER", t.plusMillis(4)),
                span("trace-a", "ledger-client", "payment-server", "payment-service",
                        "ledger-service", "HTTP POST /ledger", "CLIENT", t.plusMillis(5)),
                span("trace-a", "unrelated-client", "", "admin-service",
                        "email-service", "HTTP POST /notify", "CLIENT", t.plusMillis(6))
        ));

        var result = service.analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        );

        assertThat(result.matchedRootSpanCount()).isEqualTo(1);
        assertThat(result.matchedTraceCount()).isEqualTo(1);

        assertThat(result.downstreamEdges())
                .extracting(edge ->
                        edge.sourceService()
                                + "->"
                                + edge.targetService()
                                + "["
                                + edge.endpoint()
                                + "]"
                )
                .containsExactlyInAnyOrder(
                        "orders-service->payment-service[HTTP POST /payments]",
                        "payment-service->ledger-service[HTTP POST /ledger]"
                )
                .doesNotContain(
                        "admin-service->email-service[HTTP POST /notify]"
                );
    }

    @Test
    void aggregatesCallsAndUniqueTracesAcrossMatchingEndpointExecutions() {
        Instant t = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                span("trace-a", "root-a", "", "orders-service",
                        null, "HTTP POST /orders", "SERVER", t),
                span("trace-a", "payment-a", "root-a", "orders-service",
                        "payment-service", "HTTP POST /payments", "CLIENT", t.plusSeconds(1)),
                span("trace-b", "root-b", "", "orders-service",
                        null, "HTTP POST /orders", "SERVER", t.plusSeconds(2)),
                span("trace-b", "payment-b", "root-b", "orders-service",
                        "payment-service", "HTTP POST /payments", "CLIENT", t.plusSeconds(3))
        ));

        var result = service.analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        );

        assertThat(result.downstreamEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.callCount()).isEqualTo(2);
            assertThat(edge.traceCount()).isEqualTo(2);
            assertThat(edge.rootEndpoints())
                    .containsExactly("HTTP POST /orders");
        });
    }

    @Test
    void ignoresOtherServerEndpointsInSameService() {
        Instant t = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                span("trace-orders", "orders-root", "", "orders-service",
                        null, "HTTP POST /orders", "SERVER", t),
                span("trace-orders", "payment", "orders-root", "orders-service",
                        "payment-service", "HTTP POST /payments", "CLIENT", t.plusSeconds(1)),
                span("trace-retry", "retry-root", "", "orders-service",
                        null, "HTTP POST /orders/retry", "SERVER", t.plusSeconds(2)),
                span("trace-retry", "notify", "retry-root", "orders-service",
                        "email-service", "HTTP POST /notify", "CLIENT", t.plusSeconds(3))
        ));

        var result = service.analyze(
                "orders-service",
                Set.of("HTTP POST /orders")
        );

        assertThat(result.downstreamEdges())
                .extracting(edge -> edge.targetService())
                .containsExactly("payment-service");
    }

    private TraceSpanObservation span(
            String traceId,
            String spanId,
            String parentSpanId,
            String serviceName,
            String targetService,
            String endpoint,
            String spanKind,
            Instant observedAt
    ) {
        return new TraceSpanObservation(
                traceId,
                spanId,
                parentSpanId,
                serviceName,
                targetService,
                endpoint,
                spanKind,
                observedAt
        );
    }
}
