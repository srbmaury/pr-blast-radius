package com.srbmaury.blastradius.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantTokenServiceTest {

    @Test
    void provisionsSeparateTokensAndStoresOnlyHashes() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TenantCredentialStore(jdbc);
        store.initialize();

        var service = new TenantTokenService(store);
        var issued = service.provision("Acme");

        assertThat(issued.tenantId())
                .isEqualTo("acme");
        assertThat(issued.apiToken())
                .startsWith("br_api_");
        assertThat(issued.ingestToken())
                .startsWith("br_ingest_")
                .isNotEqualTo(issued.apiToken());

        assertThat(service.tenantForApiToken(
                issued.apiToken()
        )).contains("acme");

        assertThat(service.tenantForIngestToken(
                issued.ingestToken()
        )).contains("acme");

        var persisted = jdbc.queryForMap(
                """
                SELECT api_token_hash, ingest_token_hash
                FROM tenant_credential
                WHERE tenant_id = ?
                """,
                "acme"
        );

        assertThat(persisted.values())
                .doesNotContain(
                        issued.apiToken(),
                        issued.ingestToken()
                );
    }

    @Test
    void reprovisioningRotatesBothCredentials() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var store = new TenantCredentialStore(jdbc);
        store.initialize();

        var service = new TenantTokenService(store);
        var first = service.provision("acme");
        var second = service.provision("acme");

        assertThat(first.apiToken())
                .isNotEqualTo(second.apiToken());
        assertThat(first.ingestToken())
                .isNotEqualTo(second.ingestToken());

        assertThat(service.tenantForApiToken(
                first.apiToken()
        )).isEmpty();
        assertThat(service.tenantForIngestToken(
                first.ingestToken()
        )).isEmpty();

        assertThat(service.tenantForApiToken(
                second.apiToken()
        )).contains("acme");
    }
}
