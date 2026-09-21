package com.srbmaury.blastradius.tenant;

public final class TenantIds {

    public static final String DEFAULT = "default";

    private TenantIds() {}

    public static String normalize(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return DEFAULT;
        }

        String normalized = tenantId.trim().toLowerCase();

        if (!normalized.matches("[a-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException(
                    "Tenant id may contain only letters, numbers, '.', '_', ':', or '-'"
            );
        }

        return normalized;
    }
}
