package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.TenantCredentialsIssued;
import com.srbmaury.blastradius.tenant.AdminAccessVerifier;
import com.srbmaury.blastradius.tenant.TenantTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantProvisioningControllerTest {

    @Test
    void returnsOneTimeCredentialsForAuthorizedAdmin() {
        TenantTokenService tokens =
                mock(TenantTokenService.class);
        var admin = new AdminAccessVerifier(
                "admin-secret"
        );
        var expected = new TenantCredentialsIssued(
                "acme",
                "br_api_example",
                "br_ingest_example"
        );

        when(tokens.provision("acme"))
                .thenReturn(expected);

        var controller =
                new TenantProvisioningController(
                        tokens,
                        admin
                );

        assertThat(controller.provision(
                new TenantProvisionRequest("acme"),
                "admin-secret"
        )).isSameAs(expected);
    }

    @Test
    void rejectsWrongAdminToken() {
        TenantTokenService tokens =
                mock(TenantTokenService.class);
        var admin = new AdminAccessVerifier(
                "admin-secret"
        );

        var controller =
                new TenantProvisioningController(
                        tokens,
                        admin
                );

        assertThatThrownBy(() ->
                controller.provision(
                        new TenantProvisionRequest("acme"),
                        "wrong"
                ))
                .isInstanceOf(
                        ResponseStatusException.class
                )
                .satisfies(error ->
                        assertThat(
                                ((ResponseStatusException) error)
                                        .getStatusCode()
                                        .value()
                        ).isEqualTo(401)
                );
    }
}
