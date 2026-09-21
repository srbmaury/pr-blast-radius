package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.PullRequestRevision;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.DiffFileLineChanges;
import com.srbmaury.blastradius.ingestion.SpringEndpointOwnership;
import com.srbmaury.blastradius.ingestion.SpringEndpointOwnershipAnalyzer;
import com.srbmaury.blastradius.ingestion.UnifiedDiffLineParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SourceAwarePullRequestEnricher {

    private final GitHubPullRequestClient githubClient;
    private final UnifiedDiffLineParser diffLineParser;
    private final SpringEndpointOwnershipAnalyzer endpointAnalyzer;

    public SourceAwarePullRequestEnricher(
            GitHubPullRequestClient githubClient,
            UnifiedDiffLineParser diffLineParser,
            SpringEndpointOwnershipAnalyzer endpointAnalyzer
    ) {
        this.githubClient = githubClient;
        this.diffLineParser = diffLineParser;
        this.endpointAnalyzer = endpointAnalyzer;
    }

    public PullRequestChangeSet enrich(
            String owner,
            String repository,
            long pullRequestNumber,
            String diff,
            PullRequestChangeSet initial
    ) {
        List<DiffFileLineChanges> javaFiles = diffLineParser.parse(diff)
                .stream()
                .filter(this::isJavaChange)
                .toList();

        if (javaFiles.isEmpty()) {
            return initial;
        }

        PullRequestRevision revision = githubClient.fetchRevision(
                owner,
                repository,
                pullRequestNumber
        );

        List<DetectedChange> sourceAwareChanges = new ArrayList<>();

        for (DiffFileLineChanges file : javaFiles) {

            Map<String, SpringEndpointOwnership> baseEndpoints =
                    analyzeRevision(
                            owner,
                            repository,
                            file.oldPath(),
                            revision.baseSha(),
                            file.oldChangedLines()
                    );

            Map<String, SpringEndpointOwnership> headEndpoints =
                    analyzeRevision(
                            owner,
                            repository,
                            file.newPath(),
                            revision.headSha(),
                            file.newChangedLines()
                    );

            sourceAwareChanges.addAll(classifyEndpointChanges(
                    preferredPath(file),
                    baseEndpoints,
                    headEndpoints,
                    isExistingPath(file.oldPath()),
                    isExistingPath(file.newPath()),
                    !file.oldChangedLines().isEmpty(),
                    !file.newChangedLines().isEmpty()
            ));
        }

        return new PullRequestChangeSet(
                initial.source(),
                mergeChanges(initial.changes(), sourceAwareChanges)
        );
    }

    private Map<String, SpringEndpointOwnership> analyzeRevision(
            String owner,
            String repository,
            String path,
            String ref,
            Set<Integer> changedLines
    ) {
        if (path == null
                || "/dev/null".equals(path)
                || changedLines == null
                || changedLines.isEmpty()) {
            return Map.of();
        }

        String source = githubClient.fetchFileContent(
                owner,
                repository,
                path,
                ref
        );

        Map<String, SpringEndpointOwnership> result = new LinkedHashMap<>();

        for (SpringEndpointOwnership ownership :
                endpointAnalyzer.findOwnedEndpoints(source, changedLines)) {
            result.putIfAbsent(ownership.endpoint(), ownership);
        }

        return Map.copyOf(result);
    }

    private List<DetectedChange> classifyEndpointChanges(
            String file,
            Map<String, SpringEndpointOwnership> baseEndpoints,
            Map<String, SpringEndpointOwnership> headEndpoints,
            boolean baseFileExists,
            boolean headFileExists,
            boolean baseAnalyzed,
            boolean headAnalyzed
    ) {
        Set<String> allEndpoints = new LinkedHashSet<>();
        allEndpoints.addAll(baseEndpoints.keySet());
        allEndpoints.addAll(headEndpoints.keySet());

        List<DetectedChange> changes = new ArrayList<>();

        for (String endpoint : allEndpoints) {
            SpringEndpointOwnership base = baseEndpoints.get(endpoint);
            SpringEndpointOwnership head = headEndpoints.get(endpoint);

            ChangeOperation operation;
            String evidence;

            if (base != null && head != null) {
                operation = ChangeOperation.MODIFIED;
                evidence = head.evidence();
            } else if (head != null) {
                operation = baseFileExists && !baseAnalyzed
                        ? ChangeOperation.MODIFIED
                        : ChangeOperation.ADDED;
                evidence = head.evidence();
            } else {
                operation = headFileExists && !headAnalyzed
                        ? ChangeOperation.MODIFIED
                        : ChangeOperation.REMOVED;
                evidence = base.evidence();
            }

            changes.add(new DetectedChange(
                    ChangeKind.API_ENDPOINT,
                    operation,
                    endpoint,
                    file,
                    evidence
            ));
        }

        return List.copyOf(changes);
    }

    private List<DetectedChange> mergeChanges(
            List<DetectedChange> initial,
            List<DetectedChange> enrichment
    ) {
        List<DetectedChange> merged = new ArrayList<>(initial);
        Set<ChangeKey> seen = new LinkedHashSet<>();

        for (DetectedChange change : initial) {
            seen.add(ChangeKey.from(change));
        }

        Set<String> directlyDetectedEndpoints = initial.stream()
                .filter(change -> change.kind() == ChangeKind.API_ENDPOINT)
                .map(DetectedChange::identifier)
                .collect(java.util.stream.Collectors.toSet());

        for (DetectedChange change : enrichment) {
            if (change.kind() == ChangeKind.API_ENDPOINT
                    && directlyDetectedEndpoints.contains(change.identifier())) {
                continue;
            }

            if (seen.add(ChangeKey.from(change))) {
                merged.add(change);
            }
        }

        return List.copyOf(merged);
    }

    private boolean isJavaChange(DiffFileLineChanges file) {
        return isJavaPath(file.oldPath()) || isJavaPath(file.newPath());
    }

    private boolean isExistingPath(String path) {
        return path != null && !"/dev/null".equals(path);
    }

    private boolean isJavaPath(String path) {
        return path != null
                && !"/dev/null".equals(path)
                && path.endsWith(".java");
    }

    private String preferredPath(DiffFileLineChanges file) {
        return file.newPath() != null && !"/dev/null".equals(file.newPath())
                ? file.newPath()
                : file.oldPath();
    }

    private record ChangeKey(
            ChangeKind kind,
            ChangeOperation operation,
            String identifier
    ) {
        private static ChangeKey from(DetectedChange change) {
            return new ChangeKey(
                    change.kind(),
                    change.operation(),
                    change.identifier()
            );
        }
    }
}
