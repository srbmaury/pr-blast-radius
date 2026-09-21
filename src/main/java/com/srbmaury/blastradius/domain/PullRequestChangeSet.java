package com.srbmaury.blastradius.domain;

import java.util.List;

public record PullRequestChangeSet(
        String source,
        List<DetectedChange> changes
) {}
