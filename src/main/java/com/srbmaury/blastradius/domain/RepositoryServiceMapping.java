package com.srbmaury.blastradius.domain;

import java.time.Instant;

public record RepositoryServiceMapping(
        String repository,
        String service,
        Instant updatedAt
) {}
