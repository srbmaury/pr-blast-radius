package com.srbmaury.blastradius.domain;

public record GitHubInstallationInfo(
        long installationId,
        String accountLogin,
        String accountType,
        String status
) {}
