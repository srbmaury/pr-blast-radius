package com.srbmaury.blastradius.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnifiedDiffLineParser {

    private static final Pattern DIFF_FILE =
            Pattern.compile("^diff --git a/(.+) b/(.+)$");

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public List<DiffFileLineChanges> parse(String diff) {
        if (diff == null || diff.isBlank()) {
            return List.of();
        }

        List<DiffFileLineChanges> files = new ArrayList<>();
        MutableFile current = null;
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;

        for (String line : diff.split("\\R", -1)) {
            Matcher fileMatcher = DIFF_FILE.matcher(line);
            if (fileMatcher.matches()) {
                if (current != null) {
                    files.add(current.freeze());
                }

                current = new MutableFile(
                        fileMatcher.group(1),
                        fileMatcher.group(2)
                );
                inHunk = false;
                continue;
            }

            if (current == null) {
                continue;
            }

            if (line.startsWith("--- ")) {
                current.oldPath = normalizeDiffPath(line.substring(4).trim());
                continue;
            }

            if (line.startsWith("+++ ")) {
                current.newPath = normalizeDiffPath(line.substring(4).trim());
                continue;
            }

            Matcher hunkMatcher = HUNK.matcher(line);
            if (hunkMatcher.matches()) {
                oldLine = Integer.parseInt(hunkMatcher.group(1));
                newLine = Integer.parseInt(hunkMatcher.group(3));
                inHunk = true;
                continue;
            }

            if (!inHunk || line.isEmpty()) {
                continue;
            }

            char prefix = line.charAt(0);

            if (prefix == '-') {
                current.oldChangedLines.add(oldLine);
                oldLine++;
            } else if (prefix == '+') {
                current.newChangedLines.add(newLine);
                newLine++;
            } else if (prefix == ' ') {
                oldLine++;
                newLine++;
            } else if (prefix == '\\') {
                // "\ No newline at end of file"
            }
        }

        if (current != null) {
            files.add(current.freeze());
        }

        return List.copyOf(files);
    }

    private String normalizeDiffPath(String path) {
        if ("/dev/null".equals(path)) {
            return path;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private static final class MutableFile {
        private String oldPath;
        private String newPath;
        private final Set<Integer> oldChangedLines = new LinkedHashSet<>();
        private final Set<Integer> newChangedLines = new LinkedHashSet<>();

        private MutableFile(String oldPath, String newPath) {
            this.oldPath = oldPath;
            this.newPath = newPath;
        }

        private DiffFileLineChanges freeze() {
            return new DiffFileLineChanges(
                    oldPath,
                    newPath,
                    Set.copyOf(oldChangedLines),
                    Set.copyOf(newChangedLines)
            );
        }
    }
}
