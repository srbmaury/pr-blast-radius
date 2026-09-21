package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.ImpactAnalysisService;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pr")
public class PullRequestAnalysisController {

    private final GitHubPullRequestClient githubClient;
    private final PullRequestDiffParser diffParser;
    private final ImpactAnalysisService impactAnalysisService;
    private final ImpactReportFormatter reportFormatter;

    public PullRequestAnalysisController(
            GitHubPullRequestClient githubClient,
            PullRequestDiffParser diffParser,
            ImpactAnalysisService impactAnalysisService,
            ImpactReportFormatter reportFormatter
    ) {
        this.githubClient = githubClient;
        this.diffParser = diffParser;
        this.impactAnalysisService = impactAnalysisService;
        this.reportFormatter = reportFormatter;
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
            @PathVariable long number,
            @RequestParam(required = false) String service
    ) {
        PullRequestChangeSet changeSet = analyzeGitHubPullRequest(
                owner,
                repo,
                number
        );

        return impactAnalysisService.analyze(changeSet, service);
    }

    @PostMapping("/{owner}/{repo}/{number}/comment")
    public Map<String, Object> publishImpactComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number,
            @RequestParam(required = false) String service
    ) {
        ImpactAnalysisResponse response = analyzeGitHubPullRequestImpact(
                owner,
                repo,
                number,
                service
        );

        String report = reportFormatter.toMarkdown(response);
        githubClient.postComment(owner, repo, number, report);

        return Map.of(
                "posted",
                true,
                "report",
                report
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
    public ImpactAnalysisResponse analyzeRawDiffImpact(
            @RequestBody String diff,
            @RequestParam(required = false) String service
    ) {
        PullRequestChangeSet changeSet = analyzeRawDiff(diff);
        return impactAnalysisService.analyze(changeSet, service);
    }

    private void validateRepositoryPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Invalid GitHub owner or repository name"
            );
        }
    }
}
