package com.srbmaury.blastradius.domain;

public record StaticOutboundCall(
        String sourceMethod,
        String targetService,
        String endpoint,
        String clientKind,
        String evidence
) {}
