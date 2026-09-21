package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.domain.GitHubInstallationAccessToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubInstallationTokenServiceTest {

    @Test
    void reusesInstallationTokenUntilRefreshWindow() {
        GitHubAppClient client =
                mock(GitHubAppClient.class);

        when(client.createInstallationAccessToken(77L))
                .thenReturn(
                        new GitHubInstallationAccessToken(
                                "ghs-one",
                                Instant.now().plusSeconds(
                                        3600
                                )
                        )
                );

        var service =
                new GitHubInstallationTokenService(
                        client
                );

        assertThat(service.tokenFor(77L))
                .isEqualTo("ghs-one");
        assertThat(service.tokenFor(77L))
                .isEqualTo("ghs-one");

        verify(client, times(1))
                .createInstallationAccessToken(
                        77L
                );
    }

    @Test
    void refreshesTokenInsideFiveMinuteWindow() {
        GitHubAppClient client =
                mock(GitHubAppClient.class);

        when(client.createInstallationAccessToken(78L))
                .thenReturn(
                        new GitHubInstallationAccessToken(
                                "ghs-expiring",
                                Instant.now().plusSeconds(
                                        60
                                )
                        ),
                        new GitHubInstallationAccessToken(
                                "ghs-fresh",
                                Instant.now().plusSeconds(
                                        3600
                                )
                        )
                );

        var service =
                new GitHubInstallationTokenService(
                        client
                );

        assertThat(service.tokenFor(78L))
                .isEqualTo("ghs-expiring");
        assertThat(service.tokenFor(78L))
                .isEqualTo("ghs-fresh");

        verify(client, times(2))
                .createInstallationAccessToken(
                        78L
                );
    }
}
