package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubInstallationStore;
import com.srbmaury.blastradius.github.GitHubInstallationTokenService;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.ImpactAnalysisService;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.PullRequestAnalysisOrchestrator;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestAnalysisControllerTest {

    @Test
    void localModePreservesLegacyGitHubCredentialPath() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                null,
                null
        )).thenReturn("default");
        when(fixture.access().isAuthEnabled())
                .thenReturn(false);
        when(fixture.orchestrator().analyze(
                "default",
                "acme",
                "orders",
                42L,
                null,
                null
        )).thenReturn(fixture.expected());

        assertThat(fixture.controller()
                .analyzeGitHubPullRequestImpact(
                        "acme",
                        "orders",
                        42L,
                        null,
                        null,
                        null
                ))
                .isSameAs(fixture.expected());

        verify(fixture.orchestrator()).analyze(
                "default",
                "acme",
                "orders",
                42L,
                null,
                null
        );
    }

    @Test
    void hostedModeUsesBoundInstallationToken() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");
        when(fixture.access().isAuthEnabled())
                .thenReturn(true);
        when(fixture.installations()
                .findActiveForTenantAndAccount(
                        "tenant-a",
                        "acme"
                ))
                .thenReturn(Optional.of(
                        new GitHubInstallationInfo(
                                77L,
                                "acme",
                                "Organization",
                                "ACTIVE"
                        )
                ));
        when(fixture.tokens().tokenFor(77L))
                .thenReturn("ghs-installation");
        when(fixture.orchestrator().analyze(
                "tenant-a",
                "acme",
                "orders",
                42L,
                "orders-service",
                "ghs-installation"
        )).thenReturn(fixture.expected());

        assertThat(fixture.controller()
                .analyzeGitHubPullRequestImpact(
                        "acme",
                        "orders",
                        42L,
                        "orders-service",
                        "tenant-a",
                        "Bearer api-token"
                ))
                .isSameAs(fixture.expected());

        verify(fixture.tokens()).tokenFor(77L);
        verify(fixture.orchestrator()).analyze(
                "tenant-a",
                "acme",
                "orders",
                42L,
                "orders-service",
                "ghs-installation"
        );
    }

    @Test
    void hostedCommentUsesUpsertInsteadOfCreatingDuplicates() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");
        when(fixture.access().isAuthEnabled())
                .thenReturn(true);
        when(fixture.installations()
                .findActiveForTenantAndAccount(
                        "tenant-a",
                        "acme"
                ))
                .thenReturn(Optional.of(
                        new GitHubInstallationInfo(
                                77L,
                                "acme",
                                "Organization",
                                "ACTIVE"
                        )
                ));
        when(fixture.tokens().tokenFor(77L))
                .thenReturn("ghs-installation");
        when(fixture.orchestrator().analyze(
                "tenant-a",
                "acme",
                "orders",
                42L,
                null,
                "ghs-installation"
        )).thenReturn(fixture.expected());
        when(fixture.formatter().toMarkdown(
                fixture.expected()
        )).thenReturn("report");

        var result = fixture.controller()
                .publishImpactComment(
                        "acme",
                        "orders",
                        42L,
                        null,
                        "tenant-a",
                        "Bearer api-token"
                );

        assertThat(result.get("posted"))
                .isEqualTo(true);

        verify(fixture.github()).upsertReportComment(
                "acme",
                "orders",
                42L,
                "report",
                "ghs-installation"
        );
    }

    private Fixture fixture() {
        PullRequestAnalysisOrchestrator orchestrator =
                mock(PullRequestAnalysisOrchestrator.class);
        GitHubPullRequestClient github =
                mock(GitHubPullRequestClient.class);
        GitHubInstallationStore installations =
                mock(GitHubInstallationStore.class);
        GitHubInstallationTokenService tokens =
                mock(GitHubInstallationTokenService.class);
        PullRequestDiffParser diffParser =
                mock(PullRequestDiffParser.class);
        ImpactAnalysisService analysis =
                mock(ImpactAnalysisService.class);
        ImpactReportFormatter formatter =
                mock(ImpactReportFormatter.class);
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);

        var changeSet = new PullRequestChangeSet(
                "acme/orders#42",
                List.of()
        );
        var expected = new ImpactAnalysisResponse(
                changeSet,
                List.of()
        );

        var controller =
                new PullRequestAnalysisController(
                        orchestrator,
                        github,
                        installations,
                        tokens,
                        diffParser,
                        analysis,
                        formatter,
                        access
                );

        return new Fixture(
                controller,
                orchestrator,
                github,
                installations,
                tokens,
                formatter,
                access,
                expected
        );
    }

    private record Fixture(
            PullRequestAnalysisController controller,
            PullRequestAnalysisOrchestrator orchestrator,
            GitHubPullRequestClient github,
            GitHubInstallationStore installations,
            GitHubInstallationTokenService tokens,
            ImpactReportFormatter formatter,
            TenantAccessResolver access,
            ImpactAnalysisResponse expected
    ) {}
}
