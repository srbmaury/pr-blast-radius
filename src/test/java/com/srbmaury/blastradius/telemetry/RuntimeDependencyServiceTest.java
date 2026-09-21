package com.srbmaury.blastradius.telemetry;

import com.srbmaury.blastradius.domain.OpenTelemetrySpanObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDependencyServiceTest {

    private RuntimeDependencyStore store;
    private RuntimeDependencyService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        store = new RuntimeDependencyStore(jdbcTemplate);
        store.initialize();
        service = new RuntimeDependencyService(store);
    }

    @Test
    void countsOnlyOutboundClientOrProducerSpans() {
        Instant first = Instant.parse("2026-09-21T10:00:00Z");
        Instant second = Instant.parse("2026-09-21T10:05:00Z");

        long accepted = service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        first
                ),
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "SERVER",
                        first
                ),
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        second
                )
        ));

        assertThat(accepted).isEqualTo(2);

        assertThat(service.allEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.sourceService()).isEqualTo("checkout-service");
            assertThat(edge.targetService()).isEqualTo("orders-service");
            assertThat(edge.callCount()).isEqualTo(2);
            assertThat(edge.lastSeen()).isEqualTo(second);
        });
    }

    @Test
    void traversesDownstreamServicesWithoutLoopingOnCycles() {
        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "orders-service",
                        "payment-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "payment-service",
                        "checkout-service",
                        "CLIENT",
                        observedAt
                )
        ));

        var graph = service.downstream("checkout-service", 5);

        assertThat(graph.edges())
                .extracting(edge -> edge.sourceService() + "->" + edge.targetService())
                .containsExactlyInAnyOrder(
                        "checkout-service->orders-service",
                        "orders-service->payment-service",
                        "payment-service->checkout-service"
                );
    }

    @Test
    void respectsMaximumTraversalDepth() {
        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "orders-service",
                        "payment-service",
                        "CLIENT",
                        observedAt
                )
        ));

        var graph = service.downstream("checkout-service", 1);

        assertThat(graph.edges())
                .extracting(edge -> edge.targetService())
                .containsExactly("orders-service");
    }

    @Test
    void buildsCallerAndDependencyBlastRadius() {
        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "frontend-service",
                        "checkout-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "orders-service",
                        "payment-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "payment-service",
                        "ledger-service",
                        "CLIENT",
                        observedAt
                )
        ));

        var radius = service.blastRadius("orders-service", 2);

        assertThat(radius.callers())
                .extracting(edge -> edge.sourceService() + "->" + edge.targetService())
                .containsExactlyInAnyOrder(
                        "checkout-service->orders-service",
                        "frontend-service->checkout-service"
                );

        assertThat(radius.dependencies())
                .extracting(edge -> edge.sourceService() + "->" + edge.targetService())
                .containsExactlyInAnyOrder(
                        "orders-service->payment-service",
                        "payment-service->ledger-service"
                );
    }

    @Test
    void callerTraversalIsCycleSafe() {
        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "orders-service",
                        "checkout-service",
                        "CLIENT",
                        observedAt
                )
        ));

        var callers = service.callers("orders-service", 5);

        assertThat(callers.edges())
                .extracting(edge -> edge.sourceService() + "->" + edge.targetService())
                .containsExactlyInAnyOrder(
                        "checkout-service->orders-service",
                        "orders-service->checkout-service"
                );
    }


    @Test
    void filtersOnlyDirectCallersByChangedEndpointAndKeepsUpstreamChain() {
        Instant observedAt = Instant.parse("2026-09-21T10:00:00Z");

        service.ingest(List.of(
                new OpenTelemetrySpanObservation(
                        "frontend-service",
                        "checkout-service",
                        "HTTP POST /checkout",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "HTTP POST /orders",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "checkout-service",
                        "orders-service",
                        "HTTP GET /orders/{id}",
                        "CLIENT",
                        observedAt
                ),
                new OpenTelemetrySpanObservation(
                        "orders-service",
                        "payment-service",
                        "HTTP POST /payments",
                        "CLIENT",
                        observedAt
                )
        ));

        var radius = service.blastRadius(
                "orders-service",
                2,
                Set.of("HTTP POST /orders")
        );

        assertThat(radius.callers())
                .extracting(edge ->
                        edge.sourceService()
                                + "->"
                                + edge.targetService()
                                + "["
                                + edge.endpoint()
                                + "]"
                )
                .containsExactlyInAnyOrder(
                        "checkout-service->orders-service[HTTP POST /orders]",
                        "frontend-service->checkout-service[HTTP POST /checkout]"
                )
                .doesNotContain(
                        "checkout-service->orders-service[HTTP GET /orders/{id}]"
                );

        assertThat(radius.dependencies())
                .extracting(edge -> edge.endpoint())
                .contains("HTTP POST /payments");
    }

}
