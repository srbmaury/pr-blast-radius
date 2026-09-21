package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.catalog.RepositoryServiceCatalog;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import org.springframework.stereotype.Service;

@Service
public class PullRequestAnalysisOrchestrator {

    private final GitHubPullRequestClient githubClient;
    private final PullRequestDiffParser diffParser;
    private final SourceAwarePullRequestEnricher sourceEnricher;
    private final RepositoryServiceCatalog serviceCatalog;
    private final ImpactAnalysisService impactAnalysisService;

    public PullRequestAnalysisOrchestrator(
            GitHubPullRequestClient githubClient,
            PullRequestDiffParser diffParser,
            SourceAwarePullRequestEnricher sourceEnricher,
            RepositoryServiceCatalog serviceCatalog,
            ImpactAnalysisService impactAnalysisService
    ) {
        this.githubClient = githubClient;
        this.diffParser = diffParser;
        this.sourceEnricher = sourceEnricher;
        this.serviceCatalog = serviceCatalog;
        this.impactAnalysisService = impactAnalysisService;
    }

    public PullRequestChangeSet loadChangeSet(
            String owner,
            String repository,
            long pullRequestNumber,
            String githubAccessToken
    ) {
        validateRepositoryPart(owner);
        validateRepositoryPart(repository);

        String diff = githubAccessToken == null
                || githubAccessToken.isBlank()
                ? githubClient.fetchDiff(
                        owner,
                        repository,
                        pullRequestNumber
                )
                : githubClient.fetchDiff(
                        owner,
                        repository,
                        pullRequestNumber,
                        githubAccessToken
                );

        PullRequestChangeSet initial =
                diffParser.parse(
                        owner
                                + "/"
                                + repository
                                + "#"
                                + pullRequestNumber,
                        diff
                );

        return sourceEnricher.enrich(
                owner,
                repository,
                pullRequestNumber,
                diff,
                initial,
                githubAccessToken
        );
    }

    public ImpactAnalysisResponse analyze(
            String tenantId,
            String owner,
            String repository,
            long pullRequestNumber,
            String explicitService,
            String githubAccessToken
    ) {
        PullRequestChangeSet changeSet =
                loadChangeSet(
                        owner,
                        repository,
                        pullRequestNumber,
                        githubAccessToken
                );

        String service = explicitService != null
                && !explicitService.isBlank()
                ? explicitService.trim()
                : serviceCatalog.find(
                                tenantId,
                                owner + "/" + repository
                        )
                        .map(mapping ->
                                mapping.service())
                        .orElse(null);

        return impactAnalysisService.analyze(
                tenantId,
                changeSet,
                service
        );
    }

    private void validateRepositoryPart(String value) {
        if (value == null
                || !value.matches(
                        "[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Invalid GitHub owner or repository name"
            );
        }
    }
}
