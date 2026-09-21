package com.srbmaury.blastradius.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffLineParserTest {

    private final UnifiedDiffLineParser parser = new UnifiedDiffLineParser();

    @Test
    void extractsOldAndNewChangedLines() {
        String diff = """
                diff --git a/src/main/java/com/acme/OrderController.java b/src/main/java/com/acme/OrderController.java
                --- a/src/main/java/com/acme/OrderController.java
                +++ b/src/main/java/com/acme/OrderController.java
                @@ -10,3 +10,3 @@
                 public Order create() {
                -    return legacyCreate();
                +    return create();
                 }
                """;

        var files = parser.parse(diff);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.oldPath())
                    .isEqualTo("src/main/java/com/acme/OrderController.java");
            assertThat(file.newPath())
                    .isEqualTo("src/main/java/com/acme/OrderController.java");
            assertThat(file.oldChangedLines()).containsExactly(11);
            assertThat(file.newChangedLines()).containsExactly(11);
        });
    }

    @Test
    void preservesDeletionOnlyLineNumbers() {
        String diff = """
                diff --git a/src/main/java/com/acme/OrderController.java b/src/main/java/com/acme/OrderController.java
                --- a/src/main/java/com/acme/OrderController.java
                +++ b/src/main/java/com/acme/OrderController.java
                @@ -20,2 +20,1 @@
                -    auditLegacyOrder();
                     return order;
                """;

        var file = parser.parse(diff).getFirst();

        assertThat(file.oldChangedLines()).containsExactly(20);
        assertThat(file.newChangedLines()).isEmpty();
    }
}
