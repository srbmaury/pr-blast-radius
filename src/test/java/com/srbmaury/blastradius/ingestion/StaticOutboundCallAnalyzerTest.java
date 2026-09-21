package com.srbmaury.blastradius.ingestion;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StaticOutboundCallAnalyzerTest {

    private final StaticOutboundCallAnalyzer analyzer =
            new StaticOutboundCallAnalyzer();

    @Test
    void followsSameClassHelperAndFindsRestClientCall() {
        String source = """
                import org.springframework.web.client.RestClient;

                class OrderController {
                    private final RestClient paymentClient =
                            RestClient.builder()
                                    .baseUrl("http://payment-service")
                                    .build();

                    Order create(OrderRequest request) {
                        return persist(request);
                    }

                    Order persist(OrderRequest request) {
                        paymentClient.post()
                                .uri("/payments")
                                .retrieve();
                        return new Order();
                    }
                }
                """;

        int changedLine = lineOf(
                source,
                "return persist(request);"
        );

        var analysis = analyzer.analyze(
                source,
                Set.of(changedLine)
        );

        assertThat(analysis.outboundCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.sourceMethod())
                            .isEqualTo("OrderController#persist");
                    assertThat(call.targetService())
                            .isEqualTo("payment-service");
                    assertThat(call.endpoint())
                            .isEqualTo("HTTP POST /payments");
                    assertThat(call.clientKind())
                            .isEqualTo("RestClient");
                });
    }

    @Test
    void extractsRestTemplateAbsoluteUrlAndHttpMethod() {
        String source = """
                import org.springframework.http.HttpMethod;
                import org.springframework.web.client.RestTemplate;

                class InventoryService {
                    private RestTemplate restTemplate;

                    void reserve(String id) {
                        restTemplate.exchange(
                                "http://inventory-service/inventory/{id}",
                                HttpMethod.PUT,
                                null,
                                Void.class,
                                id
                        );
                    }
                }
                """;

        int changedLine = lineOf(
                source,
                "restTemplate.exchange("
        );

        assertThat(analyzer.analyze(
                source,
                Set.of(changedLine)
        ).outboundCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.targetService())
                            .isEqualTo("inventory-service");
                    assertThat(call.endpoint())
                            .isEqualTo(
                                    "HTTP PUT /inventory/{id}"
                            );
                    assertThat(call.clientKind())
                            .isEqualTo("RestTemplate");
                });
    }

    @Test
    void extractsWebClientWithLiteralBaseUrl() {
        String source = """
                import org.springframework.web.reactive.function.client.WebClient;

                class ShippingService {
                    private final WebClient shippingClient =
                            WebClient.builder()
                                    .baseUrl("https://shipping-service/api")
                                    .build();

                    void ship() {
                        shippingClient.post()
                                .uri("/shipments")
                                .retrieve();
                    }
                }
                """;

        int changedLine = lineOf(
                source,
                "shippingClient.post()"
        );

        assertThat(analyzer.analyze(
                source,
                Set.of(changedLine)
        ).outboundCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.targetService())
                            .isEqualTo("shipping-service");
                    assertThat(call.endpoint())
                            .isEqualTo(
                                    "HTTP POST /api/shipments"
                            );
                    assertThat(call.clientKind())
                            .isEqualTo("WebClient");
                });
    }

    @Test
    void skipsDynamicUrisInsteadOfGuessing() {
        String source = """
                import org.springframework.web.client.RestClient;

                class OrderService {
                    private final RestClient client =
                            RestClient.create();

                    void call(String uri) {
                        client.get()
                                .uri(uri)
                                .retrieve();
                    }
                }
                """;

        int changedLine = lineOf(
                source,
                ".uri(uri)"
        );

        assertThat(analyzer.analyze(
                source,
                Set.of(changedLine)
        ).outboundCalls()).isEmpty();
    }

    @Test
    void emitsResolvableFeignInvocationCandidate() {
        String source = """
                package com.acme.orders;

                import com.acme.payments.PaymentClient;

                class OrderService {
                    private PaymentClient paymentClient;

                    void create() {
                        paymentClient.createPayment("order-1");
                    }
                }
                """;

        int changedLine = lineOf(
                source,
                "paymentClient.createPayment"
        );

        assertThat(analyzer.analyze(
                source,
                Set.of(changedLine)
        ).feignInvocations())
                .singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.qualifiedClientType())
                            .isEqualTo(
                                    "com.acme.payments.PaymentClient"
                            );
                    assertThat(invocation.methodName())
                            .isEqualTo("createPayment");
                    assertThat(invocation.argumentCount())
                            .isEqualTo(1);
                });
    }

    private int lineOf(
            String source,
            String needle
    ) {
        String[] lines = source.split("\\R");

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }

        throw new IllegalArgumentException(
                "Line not found: " + needle
        );
    }
}
