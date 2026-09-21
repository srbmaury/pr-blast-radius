package com.srbmaury.blastradius.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.PullRequestAnalysisOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitHubWebhookServiceTest {

    @Test
    void openedPullRequestRunsInstallationScopedAnalysisAndPublishesNeutralResults()
            throws Exception {
        Fixture fixture = fixture();

        when(fixture.deliveries().claim(
                "delivery-1",
                "pull_request"
        )).thenReturn(true);
        when(fixture.installations().findTenant(77L))
                .thenReturn(Optional.of("tenant-a"));
        when(fixture.tokens().tokenFor(77L))
                .thenReturn("ghs-installation");

        var response = new ImpactAnalysisResponse(
                new PullRequestChangeSet(
                        "acme/orders#42",
                        List.of()
                ),
                List.of()
        );

        when(fixture.orchestrator().analyze(
                "tenant-a",
                "acme",
                "orders",
                42L,
                null,
                "ghs-installation"
        )).thenReturn(response);
        when(fixture.formatter().toMarkdown(
                response
        )).thenReturn("report");

        var payload = new ObjectMapper().readTree(
                """
                {
                  "action": "opened",
                  "installation": {"id": 77},
                  "repository": {
                    "name": "orders",
                    "owner": {"login": "acme"}
                  },
                  "pull_request": {
                    "number": 42,
                    "head": {"sha": "abc123"}
                  }
                }
                """
        );

        var result = fixture.service().handle(
                "delivery-1",
                "pull_request",
                payload
        );

        assertThat(result.status())
                .isEqualTo("PROCESSED");

        verify(fixture.github()).upsertReportComment(
                "acme",
                "orders",
                42L,
                "report",
                "ghs-installation"
        );
        verify(fixture.github()).createNeutralCheck(
                "acme",
                "orders",
                "abc123",
                "report",
                "ghs-installation"
        );
        verify(fixture.deliveries()).complete(
                "delivery-1",
                "Analyzed acme/orders#42"
        );
    }

    @Test
    void duplicateDeliveryDoesNotRunAnalysis()
            throws Exception {
        Fixture fixture = fixture();

        when(fixture.deliveries().claim(
                "delivery-2",
                "pull_request"
        )).thenReturn(false);

        var result = fixture.service().handle(
                "delivery-2",
                "pull_request",
                new ObjectMapper().readTree("{}")
        );

        assertThat(result.status())
                .isEqualTo("DUPLICATE");
        verifyNoInteractions(
                fixture.orchestrator(),
                fixture.github(),
                fixture.tokens()
        );
    }

    @Test
    void unknownInstallationIsIgnoredWithoutMintingToken()
            throws Exception {
        Fixture fixture = fixture();

        when(fixture.deliveries().claim(
                "delivery-3",
                "pull_request"
        )).thenReturn(true);
        when(fixture.installations().findTenant(88L))
                .thenReturn(Optional.empty());

        var payload = new ObjectMapper().readTree(
                """
                {
                  "action": "synchronize",
                  "installation": {"id": 88}
                }
                """
        );

        var result = fixture.service().handle(
                "delivery-3",
                "pull_request",
                payload
        );

        assertThat(result.status())
                .isEqualTo("IGNORED");
        verifyNoInteractions(
                fixture.tokens(),
                fixture.orchestrator(),
                fixture.github()
        );
    }

    @Test
    void deletedInstallationIsDisabledAndTokenCacheInvalidated()
            throws Exception {
        Fixture fixture = fixture();

        when(fixture.deliveries().claim(
                "delivery-4",
                "installation"
        )).thenReturn(true);

        var payload = new ObjectMapper().readTree(
                """
                {
                  "action": "deleted",
                  "installation": {"id": 99}
                }
                """
        );

        var result = fixture.service().handle(
                "delivery-4",
                "installation",
                payload
        );

        assertThat(result.status())
                .isEqualTo("PROCESSED");
        verify(fixture.installations())
                .markInactive(99L);
        verify(fixture.tokens())
                .invalidate(99L);
    }

    private Fixture fixture() {
        GitHubWebhookDeliveryStore deliveries =
                mock(GitHubWebhookDeliveryStore.class);
        GitHubInstallationStore installations =
                mock(GitHubInstallationStore.class);
        GitHubInstallationTokenService tokens =
                mock(GitHubInstallationTokenService.class);
        PullRequestAnalysisOrchestrator orchestrator =
                mock(PullRequestAnalysisOrchestrator.class);
        ImpactReportFormatter formatter =
                mock(ImpactReportFormatter.class);
        GitHubPullRequestClient github =
                mock(GitHubPullRequestClient.class);

        var service = new GitHubWebhookService(
                deliveries,
                installations,
                tokens,
                orchestrator,
                formatter,
                github
        );

        return new Fixture(
                service,
                deliveries,
                installations,
                tokens,
                orchestrator,
                formatter,
                github
        );
    }

    private record Fixture(
            GitHubWebhookService service,
            GitHubWebhookDeliveryStore deliveries,
            GitHubInstallationStore installations,
            GitHubInstallationTokenService tokens,
            PullRequestAnalysisOrchestrator orchestrator,
            ImpactReportFormatter formatter,
            GitHubPullRequestClient github
    ) {}
}
