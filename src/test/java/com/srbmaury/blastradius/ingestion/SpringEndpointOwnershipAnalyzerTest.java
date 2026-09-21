package com.srbmaury.blastradius.ingestion;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpringEndpointOwnershipAnalyzerTest {

    private final SpringEndpointOwnershipAnalyzer analyzer =
            new SpringEndpointOwnershipAnalyzer();

    @Test
    void mapsHandlerBodyChangeToComposedSpringEndpoint() {
        String source = """
                package com.acme.orders;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/orders")
                class OrderController {

                    @PostMapping
                    Order create(OrderRequest request) {
                        validate(request);
                        return service.create(request);
                    }

                    @GetMapping("/{id}")
                    Order get(String id) {
                        return service.get(id);
                    }
                }
                """;

        int changedLine = lineOf(source, "return service.create(request);");

        var endpoints = analyzer.findOwnedEndpoints(
                source,
                Set.of(changedLine)
        );

        assertThat(endpoints).singleElement().satisfies(ownership -> {
            assertThat(ownership.endpoint()).isEqualTo("HTTP POST /orders");
            assertThat(ownership.evidence())
                    .contains("OrderController#create");
        });
    }

    @Test
    void combinesClassAndMethodPathsForRequestMapping() {
        String source = """
                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api")
                class OrderController {

                    @RequestMapping(
                        path = "/orders/{id}",
                        method = RequestMethod.GET
                    )
                    String get(String id) {
                        return id;
                    }
                }
                """;

        int changedLine = lineOf(source, "return id;");

        assertThat(analyzer.findOwnedEndpoints(source, Set.of(changedLine)))
                .singleElement()
                .satisfies(ownership ->
                        assertThat(ownership.endpoint())
                                .isEqualTo("HTTP GET /api/orders/{id}")
                );
    }

    @Test
    void ignoresChangesOutsideMappedHandlerMethods() {
        String source = """
                class Helper {
                    String normalize(String input) {
                        return input.trim();
                    }
                }
                """;

        int changedLine = lineOf(source, "return input.trim();");

        assertThat(analyzer.findOwnedEndpoints(
                source,
                Set.of(changedLine)
        )).isEmpty();
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
    void skipsNonLiteralSpringMappingPath() {
        String source = """
                import org.springframework.web.bind.annotation.*;

                class OrderController {
                    static final String PATH = "/orders";

                    @PostMapping(PATH)
                    void create() {
                        persist();
                    }

                    void persist() {}
                }
                """;

        int changedLine = lineOf(
                source,
                "persist();"
        );

        assertThat(analyzer.findOwnedEndpoints(
                source,
                Set.of(changedLine)
        )).isEmpty();
    }

}
