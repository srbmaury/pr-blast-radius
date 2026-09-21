package com.srbmaury.blastradius.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.PullRequestRevision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class GitHubPullRequestClient {

    private static final String DIFF_MEDIA_TYPE =
            "application/vnd.github.v3.diff";
    private static final String JSON_MEDIA_TYPE =
            "application/vnd.github+json";
    private static final String RAW_MEDIA_TYPE =
            "application/vnd.github.raw+json";

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

    public PullRequestRevision fetchRevision(
            String owner,
            String repository,
            long pullRequestNumber
    ) {
        JsonNode payload = restClient.get()
                .uri(
                        "/repos/{owner}/{repo}/pulls/{number}",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .header(HttpHeaders.ACCEPT, JSON_MEDIA_TYPE)
                .headers(this::applyAuthorizationIfPresent)
                .retrieve()
                .body(JsonNode.class);

        if (payload == null) {
            throw new IllegalStateException("GitHub returned no pull request metadata");
        }

        String baseSha = payload.path("base").path("sha").asText();
        String headSha = payload.path("head").path("sha").asText();

        if (baseSha.isBlank() || headSha.isBlank()) {
            throw new IllegalStateException(
                    "GitHub pull request metadata is missing base/head revisions"
            );
        }

        return new PullRequestRevision(baseSha, headSha);
    }

    public String fetchFileContent(
            String owner,
            String repository,
            String path,
            String ref
    ) {
        String safePath = validateRepositoryPath(path);

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/contents/")
                        .path(safePath)
                        .queryParam("ref", ref)
                        .build(owner, repository))
                .header(HttpHeaders.ACCEPT, RAW_MEDIA_TYPE)
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

    private String validateRepositoryPath(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("..")
                || path.contains("\n")
                || path.contains("\r")) {
            throw new IllegalArgumentException("Invalid repository file path");
        }

        return path;
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
