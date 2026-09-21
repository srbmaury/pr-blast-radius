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
    void resolvesServiceFromCatalogWhenRequestDoesNotProvideOne() {
        GitHubPullRequestClient github = mock(GitHubPullRequestClient.class);
        PullRequestDiffParser parser = mock(PullRequestDiffParser.class);
        ImpactAnalysisService analysis = mock(ImpactAnalysisService.class);
        ImpactReportFormatter formatter = mock(ImpactReportFormatter.class);
        RepositoryServiceCatalog catalog = mock(RepositoryServiceCatalog.class);
        SourceAwarePullRequestEnricher enricher = mock(SourceAwarePullRequestEnricher.class);

        var changeSet = new PullRequestChangeSet("acme/orders#42", List.of());
        var expected = new ImpactAnalysisResponse(changeSet, List.of());

        when(github.fetchDiff("acme", "orders", 42)).thenReturn("diff");
        when(parser.parse("acme/orders#42", "diff")).thenReturn(changeSet);
        when(catalog.find("acme/orders")).thenReturn(Optional.of(
                new RepositoryServiceMapping(
                        "acme/orders",
                        "orders-service",
                        Instant.now()
                )
        ));
        when(analysis.analyze(changeSet, "orders-service")).thenReturn(expected);

        var controller = new PullRequestAnalysisController(
                github,
                parser,
                analysis,
                formatter,
                catalog
        );

        assertThat(controller.analyzeGitHubPullRequestImpact(
                "acme",
                "orders",
                42,
                null
        )).isSameAs(expected);

        verify(analysis).analyze(changeSet, "orders-service");
    }

    @Test
    void explicitServiceOverridesCatalog() {
        GitHubPullRequestClient github = mock(GitHubPullRequestClient.class);
        PullRequestDiffParser parser = mock(PullRequestDiffParser.class);
        ImpactAnalysisService analysis = mock(ImpactAnalysisService.class);
        ImpactReportFormatter formatter = mock(ImpactReportFormatter.class);
        RepositoryServiceCatalog catalog = mock(RepositoryServiceCatalog.class);

        var changeSet = new PullRequestChangeSet("acme/orders#42", List.of());
        var expected = new ImpactAnalysisResponse(changeSet, List.of());

        when(github.fetchDiff("acme", "orders", 42)).thenReturn("diff");
        when(parser.parse("acme/orders#42", "diff")).thenReturn(changeSet);
        when(analysis.analyze(changeSet, "manual-service")).thenReturn(expected);

        var controller = new PullRequestAnalysisController(
                github,
                parser,
                analysis,
                formatter,
                catalog
        );

        assertThat(controller.analyzeGitHubPullRequestImpact(
                "acme",
                "orders",
                42,
                "manual-service"
        )).isSameAs(expected);

        verify(analysis).analyze(changeSet, "manual-service");
        verifyNoInteractions(catalog);
    }
}
