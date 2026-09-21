package com.srbmaury.blastradius.domain;

public record TenantCredentialsIssued(
        String tenantId,
        String apiToken,
        String ingestToken
) {}
