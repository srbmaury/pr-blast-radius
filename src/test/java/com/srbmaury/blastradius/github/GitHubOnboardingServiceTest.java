package com.srbmaury.blastradius.github;

import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubOnboardingServiceTest {

    @Test
    void singleVerifiedInstallationBindsImmediatelyAndStateCannotReplay() {
        Fixture fixture = fixture();

        when(fixture.client().exchangeOAuthCode("code"))
                .thenReturn("user-token");
        when(fixture.client().listUserInstallations(
                "user-token"
        )).thenReturn(List.of(
                new GitHubInstallationInfo(
                        101L,
                        "acme",
                        "Organization",
                        "CANDIDATE"
                )
        ));

        var start = fixture.service().start("tenant-a");
        String state = stateFrom(start.installUrl());

        var completed = fixture.service().complete(
                "code",
                state
        );

        assertThat(completed.tenantId())
                .isEqualTo("tenant-a");
        assertThat(completed.boundInstallationId())
                .isEqualTo(101L);
        assertThat(fixture.installations()
                .findTenant(101L))
                .contains("tenant-a");

        assertThatThrownBy(() ->
                fixture.service().complete(
                        "code",
                        state
                ))
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void multipleInstallationsRequireClaimFromVerifiedCandidates() {
        Fixture fixture = fixture();

        when(fixture.client().exchangeOAuthCode("code"))
                .thenReturn("user-token");
        when(fixture.client().listUserInstallations(
                "user-token"
        )).thenReturn(List.of(
                new GitHubInstallationInfo(
                        201L,
                        "acme",
                        "Organization",
                        "CANDIDATE"
                ),
                new GitHubInstallationInfo(
                        202L,
                        "personal",
                        "User",
                        "CANDIDATE"
                )
        ));

        var start = fixture.service().start("tenant-a");
        String state = stateFrom(start.installUrl());

        var completed = fixture.service().complete(
                "code",
                state
        );

        assertThat(completed.boundInstallationId())
                .isNull();
        assertThat(completed.candidates())
                .hasSize(2);

        var claimed = fixture.service().claim(
                "tenant-a",
                202L
        );

        assertThat(claimed.installationId())
                .isEqualTo(202L);
        assertThat(fixture.installations()
                .findTenant(202L))
                .contains("tenant-a");

        assertThatThrownBy(() ->
                fixture.service().claim(
                        "tenant-b",
                        201L
                ))
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    @Test
    void candidatesAreTenantScopedAndDisappearAfterClaim() {
        Fixture fixture = fixture();

        fixture.candidates().replace(
                "tenant-a",
                List.of(
                        new GitHubInstallationInfo(
                                301L,
                                "acme",
                                "Organization",
                                "CANDIDATE"
                        )
                ),
                java.time.Instant.now()
                        .plusSeconds(300)
        );

        fixture.candidates().replace(
                "tenant-b",
                List.of(
                        new GitHubInstallationInfo(
                                401L,
                                "other",
                                "Organization",
                                "CANDIDATE"
                        )
                ),
                java.time.Instant.now()
                        .plusSeconds(300)
        );

        assertThat(fixture.service().candidates(
                "tenant-a"
        ))
                .extracting(
                        GitHubInstallationInfo::installationId
                )
                .containsExactly(301L);

        fixture.service().claim(
                "tenant-a",
                301L
        );

        assertThat(fixture.service().candidates(
                "tenant-a"
        )).isEmpty();

        assertThat(fixture.service().candidates(
                "tenant-b"
        ))
                .extracting(
                        GitHubInstallationInfo::installationId
                )
                .containsExactly(401L);
    }

    private Fixture fixture() {
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        ""
                )
        );

        var states = new GitHubInstallStateStore(jdbc);
        var candidates =
                new GitHubInstallationCandidateStore(
                        jdbc
                );
        var installations =
                new GitHubInstallationStore(jdbc);

        states.initialize();
        candidates.initialize();
        installations.initialize();

        GitHubAppClient client =
                mock(GitHubAppClient.class);

        var service = new GitHubOnboardingService(
                states,
                candidates,
                installations,
                client,
                "blast-radius"
        );

        return new Fixture(
                service,
                client,
                installations,
                candidates
        );
    }

    private String stateFrom(String url) {
        int index = url.indexOf("?state=");
        if (index < 0) {
            throw new IllegalArgumentException(
                    "State missing from install URL"
            );
        }
        return url.substring(index + 7);
    }

    private record Fixture(
            GitHubOnboardingService service,
            GitHubAppClient client,
            GitHubInstallationStore installations,
            GitHubInstallationCandidateStore candidates
    ) {}
}
