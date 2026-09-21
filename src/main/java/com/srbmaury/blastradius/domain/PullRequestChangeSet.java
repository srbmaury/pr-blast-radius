package com.srbmaury.blastradius.domain;

import java.util.List;

public record PullRequestChangeSet(
        String source,
        List<DetectedChange> changes,
        List<StaticOutboundCall> staticOutboundCalls
) {
    public PullRequestChangeSet(
            String source,
            List<DetectedChange> changes
    ) {
        this(source, changes, List.of());
    }
}
