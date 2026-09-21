package com.srbmaury.blastradius.domain;

public record PostgresQueryEvidence(
        String query,
        long calls,
        long rows,
        double totalExecutionTimeMs
) {}
