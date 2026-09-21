package com.srbmaury.blastradius.domain;

public record PullRequestRevision(
        String baseSha,
        String headSha
) {}
