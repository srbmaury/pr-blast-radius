package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.domain.GitHubInstallationAccessToken;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GitHubInstallationTokenService {

    private static final long REFRESH_SKEW_SECONDS = 300;

    private final GitHubAppClient appClient;
    private final Map<Long, GitHubInstallationAccessToken>
            cache = new ConcurrentHashMap<>();

    public GitHubInstallationTokenService(
            GitHubAppClient appClient
    ) {
        this.appClient = appClient;
    }

    public String tokenFor(long installationId) {
        GitHubInstallationAccessToken current =
                cache.get(installationId);

        if (current != null
                && current.expiresAt().isAfter(
                        Instant.now().plusSeconds(
                                REFRESH_SKEW_SECONDS
                        )
                )) {
            return current.token();
        }

        GitHubInstallationAccessToken refreshed =
                appClient.createInstallationAccessToken(
                        installationId
                );

        cache.put(installationId, refreshed);
        return refreshed.token();
    }

    public void invalidate(long installationId) {
        cache.remove(installationId);
    }
}
