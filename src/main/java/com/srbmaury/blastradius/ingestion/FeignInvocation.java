package com.srbmaury.blastradius.ingestion;

public record FeignInvocation(
        String sourceMethod,
        String qualifiedClientType,
        String methodName,
        int argumentCount,
        String evidence
) {}
