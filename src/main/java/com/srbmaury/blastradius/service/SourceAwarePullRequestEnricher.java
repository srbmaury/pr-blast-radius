package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.ChangeOperation;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.domain.PullRequestRevision;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.DiffFileLineChanges;
import com.srbmaury.blastradius.ingestion.FeignClientDefinitionAnalyzer;
import com.srbmaury.blastradius.ingestion.FeignInvocation;
import com.srbmaury.blastradius.ingestion.SpringEndpointOwnership;
import com.srbmaury.blastradius.ingestion.SpringEndpointOwnershipAnalyzer;
import com.srbmaury.blastradius.ingestion.StaticOutboundCallAnalyzer;
import com.srbmaury.blastradius.ingestion.StaticSourceAnalysis;
import com.srbmaury.blastradius.ingestion.UnifiedDiffLineParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SourceAwarePullRequestEnricher {

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("\\bpackage\\s+([A-Za-z0-9_.]+)\\s*;");

    private final GitHubPullRequestClient githubClient;
    private final UnifiedDiffLineParser diffLineParser;
    private final SpringEndpointOwnershipAnalyzer endpointAnalyzer;
    private final StaticOutboundCallAnalyzer staticOutboundAnalyzer;
    private final FeignClientDefinitionAnalyzer feignAnalyzer;

    public SourceAwarePullRequestEnricher(
            GitHubPullRequestClient githubClient,
            UnifiedDiffLineParser diffLineParser,
            SpringEndpointOwnershipAnalyzer endpointAnalyzer,
            StaticOutboundCallAnalyzer staticOutboundAnalyzer,
            FeignClientDefinitionAnalyzer feignAnalyzer
    ) {
        this.githubClient = githubClient;
        this.diffLineParser = diffLineParser;
        this.endpointAnalyzer = endpointAnalyzer;
        this.staticOutboundAnalyzer = staticOutboundAnalyzer;
        this.feignAnalyzer = feignAnalyzer;
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
        Set<StaticOutboundCall> staticOutboundCalls =
                new LinkedHashSet<>(initial.staticOutboundCalls());

        for (DiffFileLineChanges file : javaFiles) {
            String baseSource = fetchSource(
                    owner,
                    repository,
                    file.oldPath(),
                    revision.baseSha(),
                    file.oldChangedLines()
            );

            String headSource = fetchSource(
                    owner,
                    repository,
                    file.newPath(),
                    revision.headSha(),
                    file.newChangedLines()
            );

            Map<String, SpringEndpointOwnership> baseEndpoints =
                    analyzeEndpoints(
                            baseSource,
                            file.oldChangedLines()
                    );

            Map<String, SpringEndpointOwnership> headEndpoints =
                    analyzeEndpoints(
                            headSource,
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

            if (headSource == null
                    || file.newChangedLines().isEmpty()) {
                continue;
            }

            StaticSourceAnalysis staticAnalysis =
                    staticOutboundAnalyzer.analyze(
                            headSource,
                            file.newChangedLines()
                    );

            staticOutboundCalls.addAll(
                    staticAnalysis.outboundCalls()
            );

            for (FeignInvocation invocation :
                    staticAnalysis.feignInvocations()) {
                resolveFeignInvocation(
                        owner,
                        repository,
                        file.newPath(),
                        headSource,
                        revision.headSha(),
                        invocation
                ).ifPresent(staticOutboundCalls::add);
            }
        }

        return new PullRequestChangeSet(
                initial.source(),
                mergeChanges(
                        initial.changes(),
                        sourceAwareChanges
                ),
                List.copyOf(staticOutboundCalls)
        );
    }

    private String fetchSource(
            String owner,
            String repository,
            String path,
            String ref,
            Set<Integer> changedLines
    ) {
        if (!isExistingPath(path)
                || changedLines == null
                || changedLines.isEmpty()) {
            return null;
        }

        return githubClient.fetchFileContent(
                owner,
                repository,
                path,
                ref
        );
    }

    private Map<String, SpringEndpointOwnership> analyzeEndpoints(
            String source,
            Set<Integer> changedLines
    ) {
        if (source == null
                || changedLines == null
                || changedLines.isEmpty()) {
            return Map.of();
        }

        Map<String, SpringEndpointOwnership> result =
                new LinkedHashMap<>();

        for (SpringEndpointOwnership ownership :
                endpointAnalyzer.findOwnedEndpoints(
                        source,
                        changedLines
                )) {
            result.putIfAbsent(
                    ownership.endpoint(),
                    ownership
            );
        }

        return Map.copyOf(result);
    }

    private Optional<StaticOutboundCall> resolveFeignInvocation(
            String owner,
            String repository,
            String currentPath,
            String currentSource,
            String headSha,
            FeignInvocation invocation
    ) {
        String clientPath = sourcePathForType(
                currentPath,
                currentSource,
                invocation.qualifiedClientType()
        );

        if (clientPath == null) {
            return Optional.empty();
        }

        try {
            String clientSource = githubClient.fetchFileContent(
                    owner,
                    repository,
                    clientPath,
                    headSha
            );

            return feignAnalyzer.resolve(
                    clientSource,
                    invocation
            );
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    private String sourcePathForType(
            String currentPath,
            String currentSource,
            String qualifiedType
    ) {
        if (currentPath == null
                || currentSource == null
                || qualifiedType == null
                || qualifiedType.isBlank()) {
            return null;
        }

        Matcher packageMatcher =
                PACKAGE_DECLARATION.matcher(currentSource);

        String qualifiedPath = qualifiedType
                .replace('.', '/')
                + ".java";

        if (packageMatcher.find()) {
            String packagePath = packageMatcher
                    .group(1)
                    .replace('.', '/')
                    + "/";

            int packageStart = currentPath.lastIndexOf(
                    packagePath
            );

            if (packageStart >= 0) {
                return currentPath.substring(
                        0,
                        packageStart
                ) + qualifiedPath;
            }
        }

        int slash = currentPath.lastIndexOf('/');
        String directory = slash >= 0
                ? currentPath.substring(0, slash + 1)
                : "";

        int lastDot = qualifiedType.lastIndexOf('.');
        String simpleName = lastDot >= 0
                ? qualifiedType.substring(lastDot + 1)
                : qualifiedType;

        return directory + simpleName + ".java";
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
            SpringEndpointOwnership base =
                    baseEndpoints.get(endpoint);
            SpringEndpointOwnership head =
                    headEndpoints.get(endpoint);

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
        List<DetectedChange> merged =
                new ArrayList<>(initial);
        Set<ChangeKey> seen = new LinkedHashSet<>();

        for (DetectedChange change : initial) {
            seen.add(ChangeKey.from(change));
        }

        Set<String> directlyDetectedEndpoints =
                initial.stream()
                        .filter(change ->
                                change.kind()
                                        == ChangeKind.API_ENDPOINT)
                        .map(DetectedChange::identifier)
                        .collect(
                                java.util.stream.Collectors.toSet()
                        );

        for (DetectedChange change : enrichment) {
            if (change.kind() == ChangeKind.API_ENDPOINT
                    && directlyDetectedEndpoints.contains(
                            change.identifier()
                    )) {
                continue;
            }

            if (seen.add(ChangeKey.from(change))) {
                merged.add(change);
            }
        }

        return List.copyOf(merged);
    }

    private boolean isJavaChange(
            DiffFileLineChanges file
    ) {
        return isJavaPath(file.oldPath())
                || isJavaPath(file.newPath());
    }

    private boolean isExistingPath(String path) {
        return path != null
                && !"/dev/null".equals(path);
    }

    private boolean isJavaPath(String path) {
        return isExistingPath(path)
                && path.endsWith(".java");
    }

    private String preferredPath(
            DiffFileLineChanges file
    ) {
        return isExistingPath(file.newPath())
                ? file.newPath()
                : file.oldPath();
    }

    private record ChangeKey(
            ChangeKind kind,
            ChangeOperation operation,
            String identifier
    ) {
        private static ChangeKey from(
                DetectedChange change
        ) {
            return new ChangeKey(
                    change.kind(),
                    change.operation(),
                    change.identifier()
            );
        }
    }
}
