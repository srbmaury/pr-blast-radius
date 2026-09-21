package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
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

    public PullRequestAnalysisController(
            GitHubPullRequestClient githubClient,
            PullRequestDiffParser diffParser
    ) {
        this.githubClient = githubClient;
        this.diffParser = diffParser;
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

    @PostMapping(
            path = "/diff/changes",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public PullRequestChangeSet analyzeRawDiff(@RequestBody String diff) {
        return diffParser.parse("raw-diff", diff);
    }

    private void validateRepositoryPart(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid GitHub owner or repository name");
        }
    }
}
