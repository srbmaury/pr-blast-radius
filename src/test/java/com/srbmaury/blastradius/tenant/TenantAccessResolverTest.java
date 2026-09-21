package com.srbmaury.blastradius.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantAccessResolverTest {

    @Test
    void apiTokenDeterminesTenantWhenHostedAuthIsEnabled() {
        TenantTokenService tokens = mock(TenantTokenService.class);

        when(tokens.tenantForApiToken("api-secret"))
                .thenReturn(Optional.of("tenant-a"));

        var resolver = new TenantAccessResolver(
                tokens,
                true
        );

        assertThat(resolver.resolveApiTenant(
                "Bearer api-secret",
                "tenant-a"
        )).isEqualTo("tenant-a");
    }

    @Test
    void ingestTokenCannotBeUsedAsApiToken() {
        TenantTokenService tokens = mock(TenantTokenService.class);

        when(tokens.tenantForApiToken("ingest-secret"))
                .thenReturn(Optional.empty());

        var resolver = new TenantAccessResolver(
                tokens,
                true
        );

        assertThatThrownBy(() ->
                resolver.resolveApiTenant(
                        "Bearer ingest-secret",
                        null
                ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error ->
                        assertThat(
                                ((ResponseStatusException) error)
                                        .getStatusCode()
                                        .value()
                        ).isEqualTo(401)
                );
    }

    @Test
    void rejectsTenantHeaderThatDoesNotMatchToken() {
        TenantTokenService tokens = mock(TenantTokenService.class);

        when(tokens.tenantForApiToken("api-secret"))
                .thenReturn(Optional.of("tenant-a"));

        var resolver = new TenantAccessResolver(
                tokens,
                true
        );

        assertThatThrownBy(() ->
                resolver.resolveApiTenant(
                        "Bearer api-secret",
                        "tenant-b"
                ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error ->
                        assertThat(
                                ((ResponseStatusException) error)
                                        .getStatusCode()
                                        .value()
                        ).isEqualTo(403)
                );
    }

    @Test
    void authDisabledPreservesHeaderBasedLocalBehavior() {
        TenantTokenService tokens = mock(TenantTokenService.class);

        var resolver = new TenantAccessResolver(
                tokens,
                false
        );

        assertThat(resolver.resolveApiTenant(
                null,
                "Tenant-A"
        )).isEqualTo("tenant-a");

        assertThat(resolver.resolveIngestTenant(
                null,
                null
        )).isEqualTo("default");
    }
}
