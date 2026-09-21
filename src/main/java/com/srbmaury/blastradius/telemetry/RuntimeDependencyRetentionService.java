package com.srbmaury.blastradius.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class RuntimeDependencyRetentionService {

    private final RuntimeDependencyStore store;
    private final Duration retention;

    public RuntimeDependencyRetentionService(
            RuntimeDependencyStore store,
            @Value("${metadata.retention-hours:168}") long retentionHours
    ) {
        this.store = store;
        this.retention = Duration.ofHours(Math.max(1, retentionHours));
    }

    @Scheduled(
            fixedDelayString = "${metadata.cleanup-interval-ms:3600000}"
    )
    public int cleanupExpiredEdges() {
        Instant cutoff = Instant.now().minus(retention);
        return store.deleteOlderThan(cutoff);
    }
}
