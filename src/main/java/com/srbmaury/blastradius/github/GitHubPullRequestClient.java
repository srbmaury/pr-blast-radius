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

    public static final String REPORT_MARKER =
            "<!-- pr-blast-radius -->";

    private static final String DIFF_MEDIA_TYPE =
            "application/vnd.github.v3.diff";
    private static final String JSON_MEDIA_TYPE =
            "application/vnd.github+json";
    private static final String RAW_MEDIA_TYPE =
            "application/vnd.github.raw+json";

    private final RestClient restClient;
    private final String legacyToken;
    private final String apiVersion;
    private final String appSlug;

    public GitHubPullRequestClient(
            RestClient.Builder builder,
            @Value("${github.api-base-url:https://api.github.com}")
            String apiBaseUrl,
            @Value("${github.token:}")
            String legacyToken,
            @Value("${github.api-version:2026-03-10}")
            String apiVersion,
            @Value("${github.app-slug:}")
            String appSlug
    ) {
        this.restClient = builder
                .baseUrl(apiBaseUrl)
                .build();
        this.legacyToken = legacyToken;
        this.apiVersion = apiVersion;
        this.appSlug = appSlug;
    }

    public String fetchDiff(
            String owner,
            String repository,
            long pullRequestNumber
    ) {
        return fetchDiff(
                owner,
                repository,
                pullRequestNumber,
                legacyToken
        );
    }

    public String fetchDiff(
            String owner,
            String repository,
            long pullRequestNumber,
            String accessToken
    ) {
        return restClient.get()
                .uri(
                        "/repos/{owner}/{repo}/pulls/{number}",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .header(
                        HttpHeaders.ACCEPT,
                        DIFF_MEDIA_TYPE
                )
                .headers(headers ->
                        applyAuthorization(
                                headers,
                                accessToken
                        ))
                .retrieve()
                .body(String.class);
    }

    public PullRequestRevision fetchRevision(
            String owner,
            String repository,
            long pullRequestNumber
    ) {
        return fetchRevision(
                owner,
                repository,
                pullRequestNumber,
                legacyToken
        );
    }

    public PullRequestRevision fetchRevision(
            String owner,
            String repository,
            long pullRequestNumber,
            String accessToken
    ) {
        JsonNode payload = restClient.get()
                .uri(
                        "/repos/{owner}/{repo}/pulls/{number}",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .headers(headers ->
                        applyJsonAuthorization(
                                headers,
                                accessToken
                        ))
                .retrieve()
                .body(JsonNode.class);

        if (payload == null) {
            throw new IllegalStateException(
                    "GitHub returned no pull request metadata"
            );
        }

        String baseSha = payload
                .path("base")
                .path("sha")
                .asText();
        String headSha = payload
                .path("head")
                .path("sha")
                .asText();

        if (baseSha.isBlank() || headSha.isBlank()) {
            throw new IllegalStateException(
                    "GitHub pull request metadata is missing base/head revisions"
            );
        }

        return new PullRequestRevision(
                baseSha,
                headSha
        );
    }

    public String fetchFileContent(
            String owner,
            String repository,
            String path,
            String ref
    ) {
        return fetchFileContent(
                owner,
                repository,
                path,
                ref,
                legacyToken
        );
    }

    public String fetchFileContent(
            String owner,
            String repository,
            String path,
            String ref,
            String accessToken
    ) {
        String safePath =
                validateRepositoryPath(path);

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(
                                "/repos/{owner}/{repo}/contents/"
                        )
                        .path(safePath)
                        .queryParam("ref", ref)
                        .build(owner, repository))
                .header(
                        HttpHeaders.ACCEPT,
                        RAW_MEDIA_TYPE
                )
                .headers(headers ->
                        applyAuthorization(
                                headers,
                                accessToken
                        ))
                .retrieve()
                .body(String.class);
    }

    public void postComment(
            String owner,
            String repository,
            long pullRequestNumber,
            String body
    ) {
        postComment(
                owner,
                repository,
                pullRequestNumber,
                body,
                requireLegacyToken()
        );
    }

    public void postComment(
            String owner,
            String repository,
            long pullRequestNumber,
            String body,
            String accessToken
    ) {
        requireToken(accessToken);

        restClient.post()
                .uri(
                        "/repos/{owner}/{repo}/issues/{number}/comments",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers ->
                        applyJsonAuthorization(
                                headers,
                                accessToken
                        ))
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    public void upsertReportComment(
            String owner,
            String repository,
            long pullRequestNumber,
            String report,
            String accessToken
    ) {
        requireToken(accessToken);

        String body = REPORT_MARKER
                + "\n"
                + report;

        JsonNode comments = restClient.get()
                .uri(
                        "/repos/{owner}/{repo}/issues/{number}/comments?per_page=100",
                        owner,
                        repository,
                        pullRequestNumber
                )
                .headers(headers ->
                        applyJsonAuthorization(
                                headers,
                                accessToken
                        ))
                .retrieve()
                .body(JsonNode.class);

        Long existingCommentId =
                findExistingReportComment(comments);

        if (existingCommentId == null) {
            postComment(
                    owner,
                    repository,
                    pullRequestNumber,
                    body,
                    accessToken
            );
            return;
        }

        restClient.patch()
                .uri(
                        "/repos/{owner}/{repo}/issues/comments/{commentId}",
                        owner,
                        repository,
                        existingCommentId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers ->
                        applyJsonAuthorization(
                                headers,
                                accessToken
                        ))
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    public void createNeutralCheck(
            String owner,
            String repository,
            String headSha,
            String report,
            String accessToken
    ) {
        requireToken(accessToken);

        Map<String, Object> output = Map.of(
                "title",
                "Blast radius analyzed",
                "summary",
                report
        );

        Map<String, Object> body = Map.of(
                "name",
                "PR Blast Radius",
                "head_sha",
                headSha,
                "status",
                "completed",
                "conclusion",
                "neutral",
                "output",
                output
        );

        restClient.post()
                .uri(
                        "/repos/{owner}/{repo}/check-runs",
                        owner,
                        repository
                )
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers ->
                        applyJsonAuthorization(
                                headers,
                                accessToken
                        ))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Long findExistingReportComment(
            JsonNode comments
    ) {
        if (comments == null || !comments.isArray()) {
            return null;
        }

        String expectedLogin =
                appSlug == null || appSlug.isBlank()
                        ? null
                        : appSlug + "[bot]";

        for (JsonNode comment : comments) {
            String body = comment.path("body").asText();
            String type = comment
                    .path("user")
                    .path("type")
                    .asText();
            String login = comment
                    .path("user")
                    .path("login")
                    .asText();

            if (!body.contains(REPORT_MARKER)
                    || !"Bot".equals(type)) {
                continue;
            }

            if (expectedLogin != null
                    && !expectedLogin.equalsIgnoreCase(
                            login
                    )) {
                continue;
            }

            long id = comment.path("id").asLong();
            if (id > 0) {
                return id;
            }
        }

        return null;
    }

    private String validateRepositoryPath(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("..")
                || path.contains("\n")
                || path.contains("\r")) {
            throw new IllegalArgumentException(
                    "Invalid repository file path"
            );
        }

        return path;
    }

    private void applyJsonAuthorization(
            HttpHeaders headers,
            String accessToken
    ) {
        headers.set(
                HttpHeaders.ACCEPT,
                JSON_MEDIA_TYPE
        );
        applyAuthorization(
                headers,
                accessToken
        );
    }

    private void applyAuthorization(
            HttpHeaders headers,
            String accessToken
    ) {
        headers.set(
                "X-GitHub-Api-Version",
                apiVersion
        );

        if (accessToken != null
                && !accessToken.isBlank()) {
            headers.setBearerAuth(accessToken);
        }
    }

    private String requireLegacyToken() {
        requireToken(legacyToken);
        return legacyToken;
    }

    private void requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "GitHub access token is required"
            );
        }
    }
}
