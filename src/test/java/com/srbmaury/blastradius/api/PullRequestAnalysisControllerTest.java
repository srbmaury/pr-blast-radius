package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.catalog.RepositoryServiceCatalog;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.RepositoryServiceMapping;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.ImpactAnalysisService;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.SourceAwarePullRequestEnricher;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PullRequestAnalysisControllerTest {

    @Test
    void resolvesServiceFromAuthenticatedTenantCatalog() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        when(fixture.catalog().find(
                "tenant-a",
                "acme/orders"
        )).thenReturn(Optional.of(
                new RepositoryServiceMapping(
                        "acme/orders",
                        "orders-service",
                        Instant.now()
                )
        ));

        when(fixture.analysis().analyze(
                "tenant-a",
                fixture.enriched(),
                "orders-service"
        )).thenReturn(fixture.expected());

        assertThat(fixture.controller()
                .analyzeGitHubPullRequestImpact(
                        "acme",
                        "orders",
                        42,
                        null,
                        "tenant-a",
                        "Bearer api-token"
                ))
                .isSameAs(fixture.expected());

        verify(fixture.analysis()).analyze(
                "tenant-a",
                fixture.enriched(),
                "orders-service"
        );
    }

    @Test
    void explicitServiceOverridesTenantCatalog() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        when(fixture.analysis().analyze(
                "tenant-a",
                fixture.enriched(),
                "manual-service"
        )).thenReturn(fixture.expected());

        assertThat(fixture.controller()
                .analyzeGitHubPullRequestImpact(
                        "acme",
                        "orders",
                        42,
                        "manual-service",
                        "tenant-a",
                        "Bearer api-token"
                ))
                .isSameAs(fixture.expected());

        verify(fixture.analysis()).analyze(
                "tenant-a",
                fixture.enriched(),
                "manual-service"
        );
        verifyNoInteractions(fixture.catalog());
    }

    @Test
    void authDisabledResolverCanReturnDefaultTenant() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                null,
                null
        )).thenReturn("default");

        when(fixture.catalog().find(
                "default",
                "acme/orders"
        )).thenReturn(Optional.empty());

        when(fixture.analysis().analyze(
                "default",
                fixture.enriched(),
                null
        )).thenReturn(fixture.expected());

        assertThat(fixture.controller()
                .analyzeGitHubPullRequestImpact(
                        "acme",
                        "orders",
                        42,
                        null,
                        null,
                        null
                ))
                .isSameAs(fixture.expected());
    }

    @Test
    void changesEndpointAlsoRequiresTenantResolution() {
        Fixture fixture = fixture();

        when(fixture.access().resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        )).thenReturn("tenant-a");

        assertThat(fixture.controller()
                .analyzeGitHubPullRequest(
                        "acme",
                        "orders",
                        42,
                        "tenant-a",
                        "Bearer api-token"
                ))
                .isSameAs(fixture.enriched());

        verify(fixture.access()).resolveApiTenant(
                "Bearer api-token",
                "tenant-a"
        );
    }

    private Fixture fixture() {
        GitHubPullRequestClient github =
                mock(GitHubPullRequestClient.class);
        PullRequestDiffParser parser =
                mock(PullRequestDiffParser.class);
        ImpactAnalysisService analysis =
                mock(ImpactAnalysisService.class);
        ImpactReportFormatter formatter =
                mock(ImpactReportFormatter.class);
        RepositoryServiceCatalog catalog =
                mock(RepositoryServiceCatalog.class);
        SourceAwarePullRequestEnricher enricher =
                mock(SourceAwarePullRequestEnricher.class);
        TenantAccessResolver access =
                mock(TenantAccessResolver.class);

        var initial = new PullRequestChangeSet(
                "acme/orders#42",
                List.of()
        );
        var enriched = new PullRequestChangeSet(
                "acme/orders#42",
                List.of()
        );
        var expected = new ImpactAnalysisResponse(
                enriched,
                List.of()
        );

        when(github.fetchDiff(
                "acme",
                "orders",
                42
        )).thenReturn("diff");

        when(parser.parse(
                "acme/orders#42",
                "diff"
        )).thenReturn(initial);

        when(enricher.enrich(
                "acme",
                "orders",
                42,
                "diff",
                initial
        )).thenReturn(enriched);

        var controller =
                new PullRequestAnalysisController(
                        github,
                        parser,
                        analysis,
                        formatter,
                        catalog,
                        enricher,
                        access
                );

        return new Fixture(
                controller,
                analysis,
                catalog,
                access,
                enriched,
                expected
        );
    }

    private record Fixture(
            PullRequestAnalysisController controller,
            ImpactAnalysisService analysis,
            RepositoryServiceCatalog catalog,
            TenantAccessResolver access,
            PullRequestChangeSet enriched,
            ImpactAnalysisResponse expected
    ) {}
}
