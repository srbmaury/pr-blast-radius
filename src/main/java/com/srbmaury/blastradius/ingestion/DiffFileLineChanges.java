package com.srbmaury.blastradius.ingestion;

import java.util.Set;

public record DiffFileLineChanges(
        String oldPath,
        String newPath,
        Set<Integer> oldChangedLines,
        Set<Integer> newChangedLines
) {}
