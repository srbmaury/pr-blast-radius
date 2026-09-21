package com.srbmaury.blastradius.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeignClientDefinitionAnalyzerTest {

    private final FeignClientDefinitionAnalyzer analyzer =
            new FeignClientDefinitionAnalyzer();

    @Test
    void resolvesFeignServiceAndComposedRoute() {
        String source = """
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;

                @FeignClient(name = "payment-service")
                @RequestMapping("/api")
                interface PaymentClient {

                    @PostMapping("/payments")
                    Payment createPayment(String orderId);
                }
                """;

        var invocation = new FeignInvocation(
                "OrderService#create",
                "com.acme.payments.PaymentClient",
                "createPayment",
                1,
                "paymentClient.createPayment(orderId)"
        );

        assertThat(analyzer.resolve(
                source,
                invocation
        )).get().satisfies(call -> {
            assertThat(call.targetService())
                    .isEqualTo("payment-service");
            assertThat(call.endpoint())
                    .isEqualTo(
                            "HTTP POST /api/payments"
                    );
            assertThat(call.clientKind())
                    .isEqualTo("OpenFeign");
        });
    }

    @Test
    void skipsPropertyDrivenFeignServiceNames() {
        String source = """
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;

                @FeignClient(name = "${payments.service}")
                interface PaymentClient {
                    @PostMapping("/payments")
                    Payment createPayment(String orderId);
                }
                """;

        var invocation = new FeignInvocation(
                "OrderService#create",
                "com.acme.payments.PaymentClient",
                "createPayment",
                1,
                "paymentClient.createPayment(orderId)"
        );

        assertThat(analyzer.resolve(
                source,
                invocation
        )).isEmpty();
    }
}
