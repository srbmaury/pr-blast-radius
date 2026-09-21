package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record GitHubInstallStart(
        String installUrl,
        Instant expiresAt
) {}
