package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.domain.GitHubInstallCallbackResult;
import com.srbmaury.blastradius.domain.GitHubInstallStart;
import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.tenant.TenantIds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class GitHubOnboardingService {

    private static final SecureRandom RANDOM =
            new SecureRandom();
    private static final Duration STATE_TTL =
            Duration.ofMinutes(15);
    private static final Duration CANDIDATE_TTL =
            Duration.ofMinutes(15);

    private final GitHubInstallStateStore stateStore;
    private final GitHubInstallationCandidateStore
            candidateStore;
    private final GitHubInstallationStore installationStore;
    private final GitHubAppClient appClient;
    private final String appSlug;

    public GitHubOnboardingService(
            GitHubInstallStateStore stateStore,
            GitHubInstallationCandidateStore candidateStore,
            GitHubInstallationStore installationStore,
            GitHubAppClient appClient,
            @Value("${github.app-slug:}")
            String appSlug
    ) {
        this.stateStore = stateStore;
        this.candidateStore = candidateStore;
        this.installationStore = installationStore;
        this.appClient = appClient;
        this.appSlug = appSlug;
    }

    public GitHubInstallStart start(String tenantId) {
        String tenant = TenantIds.normalize(tenantId);
        String slug = requireAppSlug();
        String state = randomState();
        Instant expiresAt =
                Instant.now().plus(STATE_TTL);

        stateStore.put(
                sha256(state),
                tenant,
                expiresAt
        );

        return new GitHubInstallStart(
                "https://github.com/apps/"
                        + slug
                        + "/installations/new?state="
                        + state,
                expiresAt
        );
    }

    public GitHubInstallCallbackResult complete(
            String code,
            String state
    ) {
        if (code == null || code.isBlank()
                || state == null || state.isBlank()) {
            throw new IllegalArgumentException(
                    "GitHub OAuth code and state are required"
            );
        }

        Instant now = Instant.now();
        String tenant = stateStore.consume(
                        sha256(state),
                        now
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "GitHub install state is invalid, expired, or already used"
                        ));

        String userToken =
                appClient.exchangeOAuthCode(code);

        List<GitHubInstallationInfo> candidates =
                appClient.listUserInstallations(
                        userToken
                );

        if (candidates.isEmpty()) {
            candidateStore.deleteForTenant(tenant);
            return new GitHubInstallCallbackResult(
                    tenant,
                    null,
                    List.of()
            );
        }

        if (candidates.size() == 1) {
            GitHubInstallationInfo candidate =
                    candidates.getFirst();

            installationStore.bind(
                    tenant,
                    candidate.installationId(),
                    candidate.accountLogin(),
                    candidate.accountType()
            );
            candidateStore.deleteForTenant(tenant);

            return new GitHubInstallCallbackResult(
                    tenant,
                    candidate.installationId(),
                    List.of(candidate)
            );
        }

        candidateStore.replace(
                tenant,
                candidates,
                now.plus(CANDIDATE_TTL)
        );

        return new GitHubInstallCallbackResult(
                tenant,
                null,
                candidates
        );
    }

    public GitHubInstallationInfo claim(
            String tenantId,
            long installationId
    ) {
        String tenant = TenantIds.normalize(tenantId);
        Instant now = Instant.now();

        if (!candidateStore.isValid(
                tenant,
                installationId,
                now
        )) {
            throw new IllegalArgumentException(
                    "Installation was not verified for this tenant"
            );
        }

        GitHubInstallationInfo candidate =
                candidateStore.allValid(
                                tenant,
                                now
                        )
                        .stream()
                        .filter(value ->
                                value.installationId()
                                        == installationId)
                        .findFirst()
                        .orElseThrow();

        installationStore.bind(
                tenant,
                installationId,
                candidate.accountLogin(),
                candidate.accountType()
        );
        candidateStore.deleteForTenant(tenant);

        return installationStore.find(installationId)
                .orElseThrow();
    }


    public List<GitHubInstallationInfo> candidates(
            String tenantId
    ) {
        return candidateStore.allValid(
                TenantIds.normalize(tenantId),
                Instant.now()
        );
    }

    public List<GitHubInstallationInfo> installations(
            String tenantId
    ) {
        return installationStore.allForTenant(
                TenantIds.normalize(tenantId)
        );
    }

    private String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                    );
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to hash GitHub install state",
                    ex
            );
        }
    }

    private String requireAppSlug() {
        if (appSlug == null
                || !appSlug.matches(
                        "[A-Za-z0-9-]{1,100}")) {
            throw new IllegalStateException(
                    "github.app-slug is not configured"
            );
        }

        return appSlug;
    }
}
