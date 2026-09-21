package com.srbmaury.blastradius.ingestion;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestDiffParserTest {

    private final PullRequestDiffParser parser = new PullRequestDiffParser();

    @Test
    void detectsJavaTypeAndDroppedPostgresColumn() {
        String diff = """
                diff --git a/src/main/java/com/acme/orders/OrderService.java b/src/main/java/com/acme/orders/OrderService.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/acme/orders/OrderService.java
                +++ b/src/main/java/com/acme/orders/OrderService.java
                @@ -1,3 +1,3 @@
                -public class LegacyOrderService {
                +public class OrderService {
                 }
                diff --git a/db/migrations/V42__drop_status.sql b/db/migrations/V42__drop_status.sql
                new file mode 100644
                --- /dev/null
                +++ b/db/migrations/V42__drop_status.sql
                @@ -0,0 +1,2 @@
                +ALTER TABLE orders
                +    DROP COLUMN status;
                """;

        var result = parser.parse("acme/orders#42", diff);

        assertThat(result.changes())
                .anySatisfy(change -> {
                    assertThat(change.kind()).isEqualTo(ChangeKind.JAVA_TYPE);
                    assertThat(change.operation()).isEqualTo(ChangeOperation.ADDED);
                    assertThat(change.identifier()).isEqualTo("OrderService");
                })
                .anySatisfy(change -> {
                    assertThat(change.kind()).isEqualTo(ChangeKind.DATABASE_COLUMN);
                    assertThat(change.operation()).isEqualTo(ChangeOperation.REMOVED);
                    assertThat(change.identifier()).isEqualTo("orders.status");
                });
    }

    @Test
    void detectsRenamedPostgresColumn() {
        String diff = """
                diff --git a/db/migrations/V43__rename_status.sql b/db/migrations/V43__rename_status.sql
                --- /dev/null
                +++ b/db/migrations/V43__rename_status.sql
                @@ -0,0 +1 @@
                +ALTER TABLE orders RENAME COLUMN status TO order_status;
                """;

        var result = parser.parse("acme/orders#43", diff);

        assertThat(result.changes())
                .anySatisfy(change -> {
                    assertThat(change.kind()).isEqualTo(ChangeKind.DATABASE_COLUMN);
                    assertThat(change.operation()).isEqualTo(ChangeOperation.RENAMED);
                    assertThat(change.identifier()).isEqualTo("orders.status -> order_status");
                });
    }

    @Test
    void keepsColumnChangesScopedToTheirOwnTables() {
        String diff = """
                diff --git a/db/migrations/V44__two_tables.sql b/db/migrations/V44__two_tables.sql
                --- /dev/null
                +++ b/db/migrations/V44__two_tables.sql
                @@ -0,0 +1,2 @@
                +ALTER TABLE orders DROP COLUMN status;
                +ALTER TABLE customers ADD COLUMN risk_level text;
                """;

        var result = parser.parse("acme/orders#44", diff);

        assertThat(result.changes())
                .anySatisfy(change -> assertThat(change.identifier()).isEqualTo("orders.status"))
                .anySatisfy(change -> assertThat(change.identifier()).isEqualTo("customers.risk_level"))
                .noneSatisfy(change -> assertThat(change.identifier()).isEqualTo("orders.risk_level"))
                .noneSatisfy(change -> assertThat(change.identifier()).isEqualTo("customers.status"));
    }
}
