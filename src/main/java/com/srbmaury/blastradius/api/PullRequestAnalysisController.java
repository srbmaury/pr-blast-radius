package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.catalog.RepositoryServiceCatalog;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.ImpactAnalysisService;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.SourceAwarePullRequestEnricher;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import com.srbmaury.blastradius.tenant.TenantIds;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pr")
public class PullRequestAnalysisController {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final GitHubPullRequestClient githubClient;
    private final PullRequestDiffParser diffParser;
    private final ImpactAnalysisService impactAnalysisService;
    private final ImpactReportFormatter reportFormatter;
    private final RepositoryServiceCatalog serviceCatalog;
    private final SourceAwarePullRequestEnricher sourceAwareEnricher;
    private final TenantAccessResolver tenantAccessResolver;

    public PullRequestAnalysisController(
            GitHubPullRequestClient githubClient,
            PullRequestDiffParser diffParser,
            ImpactAnalysisService impactAnalysisService,
            ImpactReportFormatter reportFormatter,
            RepositoryServiceCatalog serviceCatalog,
            SourceAwarePullRequestEnricher sourceAwareEnricher,
            TenantAccessResolver tenantAccessResolver
    ) {
        this.githubClient = githubClient;
        this.diffParser = diffParser;
        this.impactAnalysisService = impactAnalysisService;
        this.reportFormatter = reportFormatter;
        this.serviceCatalog = serviceCatalog;
        this.sourceAwareEnricher = sourceAwareEnricher;
        this.tenantAccessResolver = tenantAccessResolver;
    }

    @GetMapping("/{owner}/{repo}/{number}/changes")
    public PullRequestChangeSet analyzeGitHubPullRequest(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        tenantAccessResolver.resolveApiTenant(
                authorization,
                tenantId
        );

        return analyzeGitHubPullRequestInternal(
                owner,
                repo,
                number
        );
    }

    @GetMapping("/{owner}/{repo}/{number}/impact")
    public ImpactAnalysisResponse analyzeGitHubPullRequestImpact(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number,
            @RequestParam(required = false) String service,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = tenantAccessResolver.resolveApiTenant(
                authorization,
                tenantId
        );
        PullRequestChangeSet changeSet =
                analyzeGitHubPullRequestInternal(
                        owner,
                        repo,
                        number
                );

        String resolvedService = resolveService(
                tenant,
                owner + "/" + repo,
                service
        );

        return impactAnalysisService.analyze(
                tenant,
                changeSet,
                resolvedService
        );
    }

    @PostMapping("/{owner}/{repo}/{number}/comment")
    public Map<String, Object> publishImpactComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number,
            @RequestParam(required = false) String service,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = tenantAccessResolver.resolveApiTenant(
                authorization,
                tenantId
        );
        PullRequestChangeSet changeSet =
                analyzeGitHubPullRequestInternal(
                        owner,
                        repo,
                        number
                );
        String resolvedService = resolveService(
                tenant,
                owner + "/" + repo,
                service
        );
        ImpactAnalysisResponse response =
                impactAnalysisService.analyze(
                        tenant,
                        changeSet,
                        resolvedService
                );

        String report = reportFormatter.toMarkdown(response);
        githubClient.postComment(owner, repo, number, report);

        return Map.of(
                "posted",
                true,
                "tenant",
                tenant,
                "report",
                report
        );
    }

    @PostMapping(
            path = "/diff/changes",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public PullRequestChangeSet analyzeRawDiff(
            @RequestBody String diff,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        tenantAccessResolver.resolveApiTenant(
                authorization,
                tenantId
        );
        return diffParser.parse("raw-diff", diff);
    }

    @PostMapping(
            path = "/diff/impact",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ImpactAnalysisResponse analyzeRawDiffImpact(
            @RequestBody String diff,
            @RequestParam(required = false) String service,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = tenantAccessResolver.resolveApiTenant(
                authorization,
                tenantId
        );
        PullRequestChangeSet changeSet =
                diffParser.parse("raw-diff", diff);

        return impactAnalysisService.analyze(
                tenant,
                changeSet,
                service
        );
    }

    private PullRequestChangeSet analyzeGitHubPullRequestInternal(
            String owner,
            String repo,
            long number
    ) {
        validateRepositoryPart(owner);
        validateRepositoryPart(repo);

        String diff = githubClient.fetchDiff(
                owner,
                repo,
                number
        );
        PullRequestChangeSet initial = diffParser.parse(
                owner + "/" + repo + "#" + number,
                diff
        );

        return sourceAwareEnricher.enrich(
                owner,
                repo,
                number,
                diff,
                initial
        );
    }

    private String resolveService(
            String tenantId,
            String repository,
            String explicitService
    ) {
        if (explicitService != null
                && !explicitService.isBlank()) {
            return explicitService.trim();
        }

        return serviceCatalog.find(
                        tenantId,
                        repository
                )
                .map(mapping -> mapping.service())
                .orElse(null);
    }

    private void validateRepositoryPart(String value) {
        if (value == null
                || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Invalid GitHub owner or repository name"
            );
        }
    }
}
