package com.srbmaury.blastradius.ingestion;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PullRequestDiffParser {

    private static final Pattern DIFF_FILE =
            Pattern.compile("^diff --git a/(.+) b/(.+)$");

    private static final Pattern JAVA_TYPE =
            Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    private static final Pattern SPRING_SHORT_MAPPING =
            Pattern.compile(
                    "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)"
                            + "\\s*(?:\\((.*)\\))?"
            );

    private static final Pattern SPRING_REQUEST_MAPPING =
            Pattern.compile("@RequestMapping\\s*\\((.*)\\)");

    private static final Pattern MAPPING_PATH =
            Pattern.compile(
                    "(?:^|\\b(?:value|path)\\s*=\\s*)\\{?\\s*\"([^\"]+)\""
            );

    private static final Pattern REQUEST_METHOD =
            Pattern.compile("RequestMethod\\.([A-Z]+)");

    private static final Map<String, String> SHORT_MAPPING_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    private static final Pattern ALTER_TABLE =
            Pattern.compile("(?i)\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([A-Za-z0-9_.$\"]+)");

    private static final Pattern ADD_COLUMN =
            Pattern.compile("(?i)\\bADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_$\"]+)");

    private static final Pattern DROP_COLUMN =
            Pattern.compile("(?i)\\bDROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?([A-Za-z0-9_$\"]+)");

    private static final Pattern RENAME_COLUMN =
            Pattern.compile("(?i)\\bRENAME\\s+COLUMN\\s+([A-Za-z0-9_$\"]+)\\s+TO\\s+([A-Za-z0-9_$\"]+)");

    public PullRequestChangeSet parse(String source, String diff) {
        if (diff == null || diff.isBlank()) {
            return new PullRequestChangeSet(source, List.of());
        }

        Set<DetectedChange> changes = new LinkedHashSet<>();
        String currentFile = null;
        StringBuilder addedSql = new StringBuilder();
        StringBuilder removedSql = new StringBuilder();

        for (String line : diff.split("\\R")) {
            Matcher fileMatcher = DIFF_FILE.matcher(line);
            if (fileMatcher.matches()) {
                flushSqlChanges(currentFile, addedSql, ChangeOperation.ADDED, changes);
                flushSqlChanges(currentFile, removedSql, ChangeOperation.REMOVED, changes);

                currentFile = fileMatcher.group(2);
                changes.add(new DetectedChange(
                        ChangeKind.FILE,
                        ChangeOperation.MODIFIED,
                        currentFile,
                        currentFile,
                        line
                ));
                continue;
            }

            if (currentFile == null || line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }

            if (line.startsWith("+") || line.startsWith("-")) {
                ChangeOperation operation = line.charAt(0) == '+'
                        ? ChangeOperation.ADDED
                        : ChangeOperation.REMOVED;
                String content = line.substring(1).trim();

                if (currentFile.endsWith(".java")) {
                    detectJavaType(currentFile, content, operation, changes);
                    detectSpringEndpoint(currentFile, content, operation, changes);
                }

                if (isSqlFile(currentFile)) {
                    StringBuilder sqlBuffer = operation == ChangeOperation.ADDED ? addedSql : removedSql;
                    sqlBuffer.append(' ').append(content);
                }
            }
        }

        flushSqlChanges(currentFile, addedSql, ChangeOperation.ADDED, changes);
        flushSqlChanges(currentFile, removedSql, ChangeOperation.REMOVED, changes);

        return new PullRequestChangeSet(source, new ArrayList<>(changes));
    }

    private void detectJavaType(
            String file,
            String content,
            ChangeOperation operation,
            Set<DetectedChange> changes
    ) {
        Matcher matcher = JAVA_TYPE.matcher(content);
        while (matcher.find()) {
            changes.add(new DetectedChange(
                    ChangeKind.JAVA_TYPE,
                    operation,
                    matcher.group(2),
                    file,
                    content
            ));
        }
    }

    private void detectSpringEndpoint(
            String file,
            String content,
            ChangeOperation operation,
            Set<DetectedChange> changes
    ) {
        Matcher shortMapping = SPRING_SHORT_MAPPING.matcher(content);
        if (shortMapping.find()) {
            String method = SHORT_MAPPING_METHODS.get(shortMapping.group(1));
            String path = shortMapping.group(2) == null
                    ? "/"
                    : extractMappingPath(shortMapping.group(2));

            if (path != null) {
                changes.add(new DetectedChange(
                        ChangeKind.API_ENDPOINT,
                        operation,
                        "HTTP " + method + " " + normalizeRoute(path),
                        file,
                        content
                ));
            }
            return;
        }

        Matcher requestMapping = SPRING_REQUEST_MAPPING.matcher(content);
        if (!requestMapping.find()) {
            return;
        }

        String arguments = requestMapping.group(1);
        Matcher methodMatcher = REQUEST_METHOD.matcher(arguments);
        String path = extractMappingPath(arguments);

        if (methodMatcher.find() && path != null) {
            changes.add(new DetectedChange(
                    ChangeKind.API_ENDPOINT,
                    operation,
                    "HTTP "
                            + methodMatcher.group(1).toUpperCase(Locale.ROOT)
                            + " "
                            + normalizeRoute(path),
                    file,
                    content
            ));
        }
    }

    private String extractMappingPath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }

        Matcher pathMatcher = MAPPING_PATH.matcher(arguments.trim());
        return pathMatcher.find() ? pathMatcher.group(1) : null;
    }

    private String normalizeRoute(String route) {
        String normalized = route == null ? "/" : route.trim();
        if (normalized.isBlank()) {
            return "/";
        }
        normalized = normalized.startsWith("/") ? normalized : "/" + normalized;
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private void flushSqlChanges(
            String file,
            StringBuilder buffer,
            ChangeOperation diffOperation,
            Set<DetectedChange> changes
    ) {
        if (file == null || buffer.isEmpty()) {
            buffer.setLength(0);
            return;
        }

        String sql = buffer.toString().replaceAll("\\s+", " ").trim();
        buffer.setLength(0);

        for (String statement : sql.split(";")) {
            analyzeSqlStatement(file, statement.trim(), diffOperation, changes);
        }
    }

    private void analyzeSqlStatement(
            String file,
            String statement,
            ChangeOperation diffOperation,
            Set<DetectedChange> changes
    ) {
        if (statement.isBlank()) {
            return;
        }

        Matcher tableMatcher = ALTER_TABLE.matcher(statement);
        if (!tableMatcher.find()) {
            return;
        }

        String table = cleanIdentifier(tableMatcher.group(1));
        changes.add(new DetectedChange(
                ChangeKind.DATABASE_TABLE,
                ChangeOperation.MODIFIED,
                table,
                file,
                tableMatcher.group()
        ));

        detectColumnChanges(file, table, statement, diffOperation, changes);
    }

    private void detectColumnChanges(
            String file,
            String table,
            String sql,
            ChangeOperation diffOperation,
            Set<DetectedChange> changes
    ) {
        Matcher rename = RENAME_COLUMN.matcher(sql);
        while (rename.find()) {
            String oldName = cleanIdentifier(rename.group(1));
            String newName = cleanIdentifier(rename.group(2));
            changes.add(new DetectedChange(
                    ChangeKind.DATABASE_COLUMN,
                    ChangeOperation.RENAMED,
                    table + "." + oldName + " -> " + newName,
                    file,
                    rename.group()
            ));
        }

        Matcher add = ADD_COLUMN.matcher(sql);
        while (add.find()) {
            changes.add(new DetectedChange(
                    ChangeKind.DATABASE_COLUMN,
                    diffOperation == ChangeOperation.ADDED ? ChangeOperation.ADDED : ChangeOperation.REMOVED,
                    table + "." + cleanIdentifier(add.group(1)),
                    file,
                    add.group()
            ));
        }

        Matcher drop = DROP_COLUMN.matcher(sql);
        while (drop.find()) {
            changes.add(new DetectedChange(
                    ChangeKind.DATABASE_COLUMN,
                    diffOperation == ChangeOperation.ADDED ? ChangeOperation.REMOVED : ChangeOperation.ADDED,
                    table + "." + cleanIdentifier(drop.group(1)),
                    file,
                    drop.group()
            ));
        }
    }

    private boolean isSqlFile(String file) {
        String lower = file.toLowerCase();
        return lower.endsWith(".sql") || lower.contains("/migration") || lower.contains("/migrations");
    }

    private String cleanIdentifier(String value) {
        return value.replace("\"", "");
    }
}
