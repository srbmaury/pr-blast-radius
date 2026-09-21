package com.srbmaury.blastradius.domain;

import java.util.List;

public record GitHubInstallCallbackResult(
        String tenantId,
        Long boundInstallationId,
        List<GitHubInstallationInfo> candidates
) {}
