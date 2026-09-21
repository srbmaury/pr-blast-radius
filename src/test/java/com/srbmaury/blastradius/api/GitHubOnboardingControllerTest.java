package com.srbmaury.blastradius.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srbmaury.blastradius.domain.GitHubInstallCallbackResult;
import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.github.GitHubOnboardingService;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubOnboardingControllerTest {

    @Test
    void callbackReturnsNoStoreBrowserPageAndNotifiesOpener() {
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);
        GitHubOnboardingService onboarding =
                mock(GitHubOnboardingService.class);

        when(onboarding.complete(
                "oauth-code",
                "install-state"
        )).thenReturn(
                new GitHubInstallCallbackResult(
                        "tenant-a",
                        77L,
                        List.of(
                                new GitHubInstallationInfo(
                                        77L,
                                        "acme",
                                        "Organization",
                                        "CANDIDATE"
                                )
                        )
                )
        );

        var controller =
                new GitHubOnboardingController(
                        access,
                        onboarding,
                        new ObjectMapper()
                );

        var response = controller.callback(
                "oauth-code",
                "install-state"
        );

        assertThat(response.getStatusCode().value())
                .isEqualTo(200);
        assertThat(response.getHeaders()
                .getCacheControl())
                .contains("no-store");
        assertThat(response.getBody())
                .contains("github-install-complete")
                .contains("\"status\":\"connected\"")
                .contains("\"tenantId\":\"tenant-a\"")
                .contains("window.opener.postMessage")
                .doesNotContain("oauth-code")
                .doesNotContain("install-state");
    }

    @Test
    void candidateListUsesAuthenticatedTenant() {
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);
        GitHubOnboardingService onboarding =
                mock(GitHubOnboardingService.class);

        when(access.resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        var expected = List.of(
                new GitHubInstallationInfo(
                        88L,
                        "acme",
                        "Organization",
                        "CANDIDATE"
                )
        );

        when(onboarding.candidates("tenant-a"))
                .thenReturn(expected);

        var controller =
                new GitHubOnboardingController(
                        access,
                        onboarding,
                        new ObjectMapper()
                );

        assertThat(controller.candidates(
                "tenant-a",
                "Bearer api-token"
        )).isEqualTo(expected);

        verify(onboarding).candidates("tenant-a");
    }
}
