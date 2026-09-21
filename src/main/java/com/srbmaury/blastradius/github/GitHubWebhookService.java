package com.srbmaury.blastradius.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.srbmaury.blastradius.domain.GitHubWebhookResult;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.PullRequestAnalysisOrchestrator;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class GitHubWebhookService {

    private static final Set<String> ANALYZED_ACTIONS =
            Set.of(
                    "opened",
                    "synchronize",
                    "reopened"
            );

    private final GitHubWebhookDeliveryStore deliveryStore;
    private final GitHubInstallationStore installationStore;
    private final GitHubInstallationTokenService tokenService;
    private final PullRequestAnalysisOrchestrator orchestrator;
    private final ImpactReportFormatter reportFormatter;
    private final GitHubPullRequestClient githubClient;

    public GitHubWebhookService(
            GitHubWebhookDeliveryStore deliveryStore,
            GitHubInstallationStore installationStore,
            GitHubInstallationTokenService tokenService,
            PullRequestAnalysisOrchestrator orchestrator,
            ImpactReportFormatter reportFormatter,
            GitHubPullRequestClient githubClient
    ) {
        this.deliveryStore = deliveryStore;
        this.installationStore = installationStore;
        this.tokenService = tokenService;
        this.orchestrator = orchestrator;
        this.reportFormatter = reportFormatter;
        this.githubClient = githubClient;
    }

    public GitHubWebhookResult handle(
            String deliveryId,
            String eventName,
            JsonNode payload
    ) {
        validateHeaderValue(
                deliveryId,
                "delivery id"
        );
        validateHeaderValue(
                eventName,
                "event name"
        );

        if (!deliveryStore.claim(
                deliveryId,
                eventName
        )) {
            return new GitHubWebhookResult(
                    deliveryId,
                    "DUPLICATE",
                    "Webhook delivery was already processed"
            );
        }

        try {
            GitHubWebhookResult result =
                    switch (eventName) {
                        case "pull_request" ->
                                handlePullRequest(
                                        deliveryId,
                                        payload
                                );
                        case "installation" ->
                                handleInstallation(
                                        deliveryId,
                                        payload
                                );
                        case "ping" ->
                                new GitHubWebhookResult(
                                        deliveryId,
                                        "IGNORED",
                                        "GitHub App ping"
                                );
                        default ->
                                new GitHubWebhookResult(
                                        deliveryId,
                                        "IGNORED",
                                        "Event is not subscribed by the analysis workflow"
                                );
                    };

            deliveryStore.complete(
                    deliveryId,
                    result.detail()
            );
            return result;
        } catch (RuntimeException ex) {
            deliveryStore.fail(
                    deliveryId,
                    ex.getMessage()
            );
            throw ex;
        }
    }

    private GitHubWebhookResult handlePullRequest(
            String deliveryId,
            JsonNode payload
    ) {
        String action = payload.path("action").asText();

        if (!ANALYZED_ACTIONS.contains(action)) {
            return new GitHubWebhookResult(
                    deliveryId,
                    "IGNORED",
                    "Pull request action " + action
                            + " does not require analysis"
            );
        }

        long installationId = payload
                .path("installation")
                .path("id")
                .asLong();

        String tenant = installationStore.findTenant(
                        installationId
                )
                .orElse(null);

        if (tenant == null) {
            return new GitHubWebhookResult(
                    deliveryId,
                    "IGNORED",
                    "GitHub installation is not bound to a tenant"
            );
        }

        String owner = payload
                .path("repository")
                .path("owner")
                .path("login")
                .asText();
        String repository = payload
                .path("repository")
                .path("name")
                .asText();
        long pullRequestNumber = payload
                .path("pull_request")
                .path("number")
                .asLong();
        String headSha = payload
                .path("pull_request")
                .path("head")
                .path("sha")
                .asText();

        if (owner.isBlank()
                || repository.isBlank()
                || pullRequestNumber <= 0
                || headSha.isBlank()) {
            throw new IllegalArgumentException(
                    "GitHub pull_request payload is missing repository, PR number, or head SHA"
            );
        }

        String accessToken =
                tokenService.tokenFor(installationId);

        ImpactAnalysisResponse response =
                orchestrator.analyze(
                        tenant,
                        owner,
                        repository,
                        pullRequestNumber,
                        null,
                        accessToken
                );

        String report =
                reportFormatter.toMarkdown(response);

        githubClient.upsertReportComment(
                owner,
                repository,
                pullRequestNumber,
                report,
                accessToken
        );

        githubClient.createNeutralCheck(
                owner,
                repository,
                headSha,
                report,
                accessToken
        );

        return new GitHubWebhookResult(
                deliveryId,
                "PROCESSED",
                "Analyzed "
                        + owner
                        + "/"
                        + repository
                        + "#"
                        + pullRequestNumber
        );
    }

    private GitHubWebhookResult handleInstallation(
            String deliveryId,
            JsonNode payload
    ) {
        String action =
                payload.path("action").asText();
        long installationId = payload
                .path("installation")
                .path("id")
                .asLong();

        if (installationId <= 0) {
            throw new IllegalArgumentException(
                    "GitHub installation payload is missing installation id"
            );
        }

        if ("deleted".equals(action)
                || "suspend".equals(action)) {
            installationStore.markInactive(
                    installationId
            );
            tokenService.invalidate(
                    installationId
            );

            return new GitHubWebhookResult(
                    deliveryId,
                    "PROCESSED",
                    "Installation marked inactive"
            );
        }

        if ("unsuspend".equals(action)) {
            installationStore.markActive(
                    installationId
            );
            tokenService.invalidate(
                    installationId
            );

            return new GitHubWebhookResult(
                    deliveryId,
                    "PROCESSED",
                    "Installation reactivated"
            );
        }

        return new GitHubWebhookResult(
                deliveryId,
                "IGNORED",
                "Installation action "
                        + action
                        + " does not change an existing binding"
        );
    }

    private void validateHeaderValue(
            String value,
            String field
    ) {
        if (value == null
                || !value.matches(
                        "[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException(
                    "Invalid GitHub " + field
            );
        }
    }
}
