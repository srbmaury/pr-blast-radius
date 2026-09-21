package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record GitHubInstallationAccessToken(
        String token,
        Instant expiresAt
) {}
