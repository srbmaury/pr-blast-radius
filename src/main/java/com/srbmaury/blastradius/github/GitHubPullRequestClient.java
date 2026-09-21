package com.srbmaury.blastradius.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class GitHubPullRequestClient {

    private static final String DIFF_MEDIA_TYPE = "application/vnd.github.v3.diff";

    private final RestClient restClient;
    private final String token;

    public GitHubPullRequestClient(
            RestClient.Builder builder,
            @Value("${github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${github.token:}") String token
    ) {
        this.restClient = builder.baseUrl(apiBaseUrl).build();
        this.token = token;
    }

    public String fetchDiff(String owner, String repository, long pullRequestNumber) {
        return restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repository, pullRequestNumber)
                .header(HttpHeaders.ACCEPT, DIFF_MEDIA_TYPE)
                .headers(this::applyAuthorizationIfPresent)
                .retrieve()
                .body(String.class);
    }

    public void postComment(
            String owner,
            String repository,
            long pullRequestNumber,
            String body
    ) {
        requireToken();

        restClient.post()
                .uri(
                        "/repos/{owner}/{repo}/issues/{number}/comments",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyAuthorizationIfPresent)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    private void applyAuthorizationIfPresent(HttpHeaders headers) {
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private void requireToken() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "github.token is required to publish PR comments"
            );
        }
    }
}
