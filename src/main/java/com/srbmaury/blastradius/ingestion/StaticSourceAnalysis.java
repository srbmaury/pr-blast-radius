package com.srbmaury.blastradius.ingestion;

import com.srbmaury.blastradius.domain.StaticOutboundCall;

import java.util.List;

public record StaticSourceAnalysis(
        List<StaticOutboundCall> outboundCalls,
        List<FeignInvocation> feignInvocations
) {}
