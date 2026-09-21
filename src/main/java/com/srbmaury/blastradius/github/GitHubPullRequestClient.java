package com.srbmaury.blastradius.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                .headers(headers -> {
                    if (token != null && !token.isBlank()) {
                        headers.setBearerAuth(token);
                    }
                })
                .retrieve()
                .body(String.class);
    }
}
