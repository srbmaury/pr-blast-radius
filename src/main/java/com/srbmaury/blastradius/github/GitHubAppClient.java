package com.srbmaury.blastradius.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.GitHubInstallationAccessToken;
import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class GitHubAppClient {

    private static final String ACCEPT =
            "application/vnd.github+json";

    private final RestClient apiClient;
    private final RestClient webClient;
    private final GitHubAppAuthenticationService appAuthentication;
    private final String clientId;
    private final String clientSecret;
    private final String apiVersion;

    public GitHubAppClient(
            RestClient.Builder builder,
            GitHubAppAuthenticationService appAuthentication,
            @Value("${github.api-base-url:https://api.github.com}")
            String apiBaseUrl,
            @Value("${github.web-base-url:https://github.com}")
            String webBaseUrl,
            @Value("${github.app-client-id:}")
            String clientId,
            @Value("${github.app-client-secret:}")
            String clientSecret,
            @Value("${github.api-version:2026-03-10}")
            String apiVersion
    ) {
        this.apiClient = builder.clone()
                .baseUrl(apiBaseUrl)
                .build();
        this.webClient = builder.clone()
                .baseUrl(webBaseUrl)
                .build();
        this.appAuthentication = appAuthentication;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.apiVersion = apiVersion;
    }

    public String exchangeOAuthCode(String code) {
        requireOAuthConfigured();

        LinkedMultiValueMap<String, String> body =
                new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);

        JsonNode response = webClient.post()
                .uri("/login/oauth/access_token")
                .header(HttpHeaders.ACCEPT, "application/json")
                .contentType(
                        MediaType.APPLICATION_FORM_URLENCODED
                )
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String token = response == null
                ? ""
                : response.path("access_token").asText();

        if (token.isBlank()) {
            throw new IllegalStateException(
                    "GitHub OAuth did not return a user access token"
            );
        }

        return token;
    }

    public List<GitHubInstallationInfo> listUserInstallations(
            String userAccessToken
    ) {
        List<GitHubInstallationInfo> result =
                new ArrayList<>();

        for (int page = 1; page <= 100; page++) {
            JsonNode response = apiClient.get()
                    .uri(
                            "/user/installations?per_page=100&page={page}",
                            page
                    )
                    .headers(headers -> applyApiHeaders(
                            headers,
                            userAccessToken
                    ))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                break;
            }

            JsonNode installations =
                    response.path("installations");

            if (!installations.isArray()
                    || installations.isEmpty()) {
                break;
            }

            for (JsonNode installation :
                    installations) {
                long id = installation
                        .path("id")
                        .asLong();
                String login = installation
                        .path("account")
                        .path("login")
                        .asText();
                String type = installation
                        .path("account")
                        .path("type")
                        .asText();

                if (id > 0 && !login.isBlank()) {
                    result.add(
                            new GitHubInstallationInfo(
                                    id,
                                    login,
                                    type.isBlank()
                                            ? "Unknown"
                                            : type,
                                    "CANDIDATE"
                            )
                    );
                }
            }

            if (installations.size() < 100) {
                break;
            }
        }

        return List.copyOf(result);
    }

    public GitHubInstallationAccessToken
            createInstallationAccessToken(
                    long installationId
            ) {
        String jwt = appAuthentication.createJwt();

        JsonNode response = apiClient.post()
                .uri(
                        "/app/installations/{id}/access_tokens",
                        installationId
                )
                .headers(headers -> applyApiHeaders(
                        headers,
                        jwt
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(JsonNode.class);

        String token = response == null
                ? ""
                : response.path("token").asText();
        String expiresAt = response == null
                ? ""
                : response.path("expires_at").asText();

        if (token.isBlank() || expiresAt.isBlank()) {
            throw new IllegalStateException(
                    "GitHub did not return an installation access token"
            );
        }

        return new GitHubInstallationAccessToken(
                token,
                Instant.parse(expiresAt)
        );
    }

    private void applyApiHeaders(
            HttpHeaders headers,
            String token
    ) {
        headers.set(HttpHeaders.ACCEPT, ACCEPT);
        headers.set("X-GitHub-Api-Version", apiVersion);
        headers.setBearerAuth(token);
    }

    private void requireOAuthConfigured() {
        if (clientId == null
                || clientId.isBlank()
                || clientSecret == null
                || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "GitHub App OAuth client id/secret are not configured"
            );
        }
    }
}
