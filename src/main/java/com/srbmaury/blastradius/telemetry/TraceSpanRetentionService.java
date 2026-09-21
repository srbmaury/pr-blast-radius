package com.srbmaury.blastradius.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TraceSpanRetentionService {

    private final TraceSpanStore store;
    private final Duration retention;

    public TraceSpanRetentionService(
            TraceSpanStore store,
            @Value("${metadata.trace-retention-hours:24}") long retentionHours
    ) {
        this.store = store;
        this.retention = Duration.ofHours(Math.max(1, retentionHours));
    }

    @Scheduled(
            fixedDelayString = "${metadata.cleanup-interval-ms:3600000}"
    )
    public int cleanupExpiredSpans() {
        return store.deleteOlderThan(Instant.now().minus(retention));
    }
}
