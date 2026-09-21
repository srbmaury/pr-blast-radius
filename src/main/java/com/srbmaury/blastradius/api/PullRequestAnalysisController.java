package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.DatabaseImpactService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pr")
public class PullRequestAnalysisController {

    private final GitHubPullRequestClient githubClient;
    private final PullRequestDiffParser diffParser;
    private final DatabaseImpactService databaseImpactService;

    public PullRequestAnalysisController(
            GitHubPullRequestClient githubClient,
            PullRequestDiffParser diffParser,
            DatabaseImpactService databaseImpactService
    ) {
        this.githubClient = githubClient;
        this.diffParser = diffParser;
        this.databaseImpactService = databaseImpactService;
    }

    @GetMapping("/{owner}/{repo}/{number}/changes")
    public PullRequestChangeSet analyzeGitHubPullRequest(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number
    ) {
        validateRepositoryPart(owner);
        validateRepositoryPart(repo);

        String diff = githubClient.fetchDiff(owner, repo, number);
        return diffParser.parse(owner + "/" + repo + "#" + number, diff);
    }

    @GetMapping("/{owner}/{repo}/{number}/impact")
    public ImpactAnalysisResponse analyzeGitHubPullRequestImpact(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number
    ) {
        PullRequestChangeSet changeSet = analyzeGitHubPullRequest(owner, repo, number);
        return new ImpactAnalysisResponse(
                changeSet,
                databaseImpactService.analyze(changeSet)
        );
    }

    @PostMapping(
            path = "/diff/changes",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public PullRequestChangeSet analyzeRawDiff(@RequestBody String diff) {
        return diffParser.parse("raw-diff", diff);
    }

    @PostMapping(
            path = "/diff/impact",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ImpactAnalysisResponse analyzeRawDiffImpact(@RequestBody String diff) {
        PullRequestChangeSet changeSet = analyzeRawDiff(diff);
        return new ImpactAnalysisResponse(
                changeSet,
                databaseImpactService.analyze(changeSet)
        );
    }

    private void validateRepositoryPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid GitHub owner or repository name");
        }
    }
}
