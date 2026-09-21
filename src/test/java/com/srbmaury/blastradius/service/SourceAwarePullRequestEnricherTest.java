package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.PullRequestRevision;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.FeignClientDefinitionAnalyzer;
import com.srbmaury.blastradius.ingestion.SpringEndpointOwnershipAnalyzer;
import com.srbmaury.blastradius.ingestion.StaticOutboundCallAnalyzer;
import com.srbmaury.blastradius.ingestion.UnifiedDiffLineParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceAwarePullRequestEnricherTest {

    @Test
    void mapsHandlerBodyReplacementToOwningEndpoint() {
        GitHubPullRequestClient github = mock(GitHubPullRequestClient.class);

        String base = sourceWithBody("return service.createLegacy(request);");
        String head = sourceWithBody("return service.create(request);");
        int line = lineOf(base, "return service.createLegacy(request);");

        String diff = """
                diff --git a/src/main/java/com/acme/orders/OrderController.java b/src/main/java/com/acme/orders/OrderController.java
                --- a/src/main/java/com/acme/orders/OrderController.java
                +++ b/src/main/java/com/acme/orders/OrderController.java
                @@ -%d,1 +%d,1 @@
                -        return service.createLegacy(request);
                +        return service.create(request);
                """.formatted(line, line);

        when(github.fetchRevision("acme", "orders", 42))
                .thenReturn(new PullRequestRevision("base-sha", "head-sha"));
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/orders/OrderController.java",
                "base-sha"
        )).thenReturn(base);
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/orders/OrderController.java",
                "head-sha"
        )).thenReturn(head);

        var enricher = new SourceAwarePullRequestEnricher(
                github,
                new UnifiedDiffLineParser(),
                new SpringEndpointOwnershipAnalyzer(),
                new StaticOutboundCallAnalyzer(),
                new FeignClientDefinitionAnalyzer()
        );

        var result = enricher.enrich(
                "acme",
                "orders",
                42,
                diff,
                new PullRequestChangeSet("acme/orders#42", List.of())
        );

        assertThat(result.changes())
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.kind()).isEqualTo(ChangeKind.API_ENDPOINT);
                    assertThat(change.operation()).isEqualTo(ChangeOperation.MODIFIED);
                    assertThat(change.identifier()).isEqualTo("HTTP POST /orders");
                    assertThat(change.evidence()).contains("OrderController#create");
                });
    }

    @Test
    void deletionOnlyBodyEditStillMarksEndpointModified() {
        GitHubPullRequestClient github = mock(GitHubPullRequestClient.class);

        String base = sourceWithTwoStatements(
                "auditLegacy(request);",
                "return service.create(request);"
        );
        String head = sourceWithBody("return service.create(request);");
        int deletedLine = lineOf(base, "auditLegacy(request);");

        String diff = """
                diff --git a/src/main/java/com/acme/orders/OrderController.java b/src/main/java/com/acme/orders/OrderController.java
                --- a/src/main/java/com/acme/orders/OrderController.java
                +++ b/src/main/java/com/acme/orders/OrderController.java
                @@ -%d,1 +%d,0 @@
                -        auditLegacy(request);
                """.formatted(deletedLine, deletedLine);

        when(github.fetchRevision("acme", "orders", 43))
                .thenReturn(new PullRequestRevision("base-sha", "head-sha"));
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/orders/OrderController.java",
                "base-sha"
        )).thenReturn(base);

        var enricher = new SourceAwarePullRequestEnricher(
                github,
                new UnifiedDiffLineParser(),
                new SpringEndpointOwnershipAnalyzer(),
                new StaticOutboundCallAnalyzer(),
                new FeignClientDefinitionAnalyzer()
        );

        var result = enricher.enrich(
                "acme",
                "orders",
                43,
                diff,
                new PullRequestChangeSet("acme/orders#43", List.of())
        );

        assertThat(result.changes())
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.kind()).isEqualTo(ChangeKind.API_ENDPOINT);
                    assertThat(change.operation()).isEqualTo(ChangeOperation.MODIFIED);
                    assertThat(change.identifier()).isEqualTo("HTTP POST /orders");
                });
    }

    private String sourceWithBody(String statement) {
        return """
                package com.acme.orders;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/orders")
                class OrderController {

                    @PostMapping
                    Order create(OrderRequest request) {
                        %s
                    }
                }
                """.formatted(statement);
    }

    private String sourceWithTwoStatements(
            String first,
            String second
    ) {
        return """
                package com.acme.orders;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/orders")
                class OrderController {

                    @PostMapping
                    Order create(OrderRequest request) {
                        %s
                        %s
                    }
                }
                """.formatted(first, second);
    }

    private int lineOf(String source, String needle) {
        String[] lines = source.split("\\R");

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }

        throw new IllegalArgumentException("Line not found: " + needle);
    }

    @Test
    void resolvesImportedFeignClientIntoStaticOutboundEvidence() {
        GitHubPullRequestClient github = mock(GitHubPullRequestClient.class);

        String source = """
                package com.acme.orders;

                import com.acme.payments.PaymentClient;
                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/orders")
                class OrderController {
                    private PaymentClient paymentClient;

                    @PostMapping
                    Order create(String orderId) {
                        paymentClient.createPayment(orderId);
                        return new Order();
                    }
                }
                """;

        String feignSource = """
                package com.acme.payments;

                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;

                @FeignClient(name = "payment-service")
                interface PaymentClient {
                    @PostMapping("/payments")
                    Payment createPayment(String orderId);
                }
                """;

        int changedLine = lineOf(
                source,
                "paymentClient.createPayment"
        );

        String diff = """
                diff --git a/src/main/java/com/acme/orders/OrderController.java b/src/main/java/com/acme/orders/OrderController.java
                --- a/src/main/java/com/acme/orders/OrderController.java
                +++ b/src/main/java/com/acme/orders/OrderController.java
                @@ -%d,1 +%d,1 @@
                -        legacyPayment(orderId);
                +        paymentClient.createPayment(orderId);
                """.formatted(changedLine, changedLine);

        when(github.fetchRevision("acme", "orders", 44))
                .thenReturn(new PullRequestRevision("base-sha", "head-sha"));
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/orders/OrderController.java",
                "base-sha"
        )).thenReturn(source.replace(
                "paymentClient.createPayment(orderId);",
                "legacyPayment(orderId);"
        ));
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/orders/OrderController.java",
                "head-sha"
        )).thenReturn(source);
        when(github.fetchFileContent(
                "acme",
                "orders",
                "src/main/java/com/acme/payments/PaymentClient.java",
                "head-sha"
        )).thenReturn(feignSource);

        var enricher = new SourceAwarePullRequestEnricher(
                github,
                new UnifiedDiffLineParser(),
                new SpringEndpointOwnershipAnalyzer(),
                new StaticOutboundCallAnalyzer(),
                new FeignClientDefinitionAnalyzer()
        );

        var result = enricher.enrich(
                "acme",
                "orders",
                44,
                diff,
                new PullRequestChangeSet(
                        "acme/orders#44",
                        List.of()
                )
        );

        assertThat(result.staticOutboundCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.targetService())
                            .isEqualTo("payment-service");
                    assertThat(call.endpoint())
                            .isEqualTo("HTTP POST /payments");
                    assertThat(call.clientKind())
                            .isEqualTo("OpenFeign");
                });
    }

}
