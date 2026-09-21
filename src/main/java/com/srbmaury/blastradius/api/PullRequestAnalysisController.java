package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.github.GitHubInstallationStore;
import com.srbmaury.blastradius.github.GitHubInstallationTokenService;
import com.srbmaury.blastradius.github.GitHubPullRequestClient;
import com.srbmaury.blastradius.ingestion.PullRequestDiffParser;
import com.srbmaury.blastradius.service.ImpactAnalysisService;
import com.srbmaury.blastradius.service.ImpactReportFormatter;
import com.srbmaury.blastradius.service.PullRequestAnalysisOrchestrator;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pr")
public class PullRequestAnalysisController {

    private static final String TENANT_HEADER =
            "X-Tenant-ID";

    private final PullRequestAnalysisOrchestrator orchestrator;
    private final GitHubPullRequestClient githubClient;
    private final GitHubInstallationStore installationStore;
    private final GitHubInstallationTokenService
            installationTokenService;
    private final PullRequestDiffParser diffParser;
    private final ImpactAnalysisService impactAnalysisService;
    private final ImpactReportFormatter reportFormatter;
    private final TenantAccessResolver tenantAccessResolver;

    public PullRequestAnalysisController(
            PullRequestAnalysisOrchestrator orchestrator,
            GitHubPullRequestClient githubClient,
            GitHubInstallationStore installationStore,
            GitHubInstallationTokenService
                    installationTokenService,
            PullRequestDiffParser diffParser,
            ImpactAnalysisService impactAnalysisService,
            ImpactReportFormatter reportFormatter,
            TenantAccessResolver tenantAccessResolver
    ) {
        this.orchestrator = orchestrator;
        this.githubClient = githubClient;
        this.installationStore = installationStore;
        this.installationTokenService =
                installationTokenService;
        this.diffParser = diffParser;
        this.impactAnalysisService =
                impactAnalysisService;
        this.reportFormatter = reportFormatter;
        this.tenantAccessResolver =
                tenantAccessResolver;
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
        String tenant =
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                );

        return orchestrator.loadChangeSet(
                owner,
                repo,
                number,
                githubAccessToken(
                        tenant,
                        owner
                )
        );
    }

    @GetMapping("/{owner}/{repo}/{number}/impact")
    public ImpactAnalysisResponse
            analyzeGitHubPullRequestImpact(
                    @PathVariable String owner,
                    @PathVariable String repo,
                    @PathVariable long number,
                    @RequestParam(required = false)
                    String service,
                    @RequestHeader(
                            value = TENANT_HEADER,
                            required = false
                    ) String tenantId,
                    @RequestHeader(
                            value = HttpHeaders.AUTHORIZATION,
                            required = false
                    ) String authorization
            ) {
        String tenant =
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                );

        return orchestrator.analyze(
                tenant,
                owner,
                repo,
                number,
                service,
                githubAccessToken(
                        tenant,
                        owner
                )
        );
    }

    @PostMapping("/{owner}/{repo}/{number}/comment")
    public Map<String, Object> publishImpactComment(
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable long number,
            @RequestParam(required = false)
            String service,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant =
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                );
        String githubToken =
                githubAccessToken(
                        tenant,
                        owner
                );

        ImpactAnalysisResponse response =
                orchestrator.analyze(
                        tenant,
                        owner,
                        repo,
                        number,
                        service,
                        githubToken
                );

        String report =
                reportFormatter.toMarkdown(response);

        if (githubToken == null) {
            githubClient.postComment(
                    owner,
                    repo,
                    number,
                    report
            );
        } else {
            githubClient.upsertReportComment(
                    owner,
                    repo,
                    number,
                    report,
                    githubToken
            );
        }

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

        return diffParser.parse(
                "raw-diff",
                diff
        );
    }

    @PostMapping(
            path = "/diff/impact",
            consumes = MediaType.TEXT_PLAIN_VALUE
    )
    public ImpactAnalysisResponse
            analyzeRawDiffImpact(
                    @RequestBody String diff,
                    @RequestParam(required = false)
                    String service,
                    @RequestHeader(
                            value = TENANT_HEADER,
                            required = false
                    ) String tenantId,
                    @RequestHeader(
                            value = HttpHeaders.AUTHORIZATION,
                            required = false
                    ) String authorization
            ) {
        String tenant =
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                );

        PullRequestChangeSet changeSet =
                diffParser.parse(
                        "raw-diff",
                        diff
                );

        return impactAnalysisService.analyze(
                tenant,
                changeSet,
                service
        );
    }

    private String githubAccessToken(
            String tenantId,
            String owner
    ) {
        if (!tenantAccessResolver.isAuthEnabled()) {
            return null;
        }

        var installation =
                installationStore
                        .findActiveForTenantAndAccount(
                                tenantId,
                                owner
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "GitHub App is not installed for repository owner "
                                                + owner
                                ));

        return installationTokenService.tokenFor(
                installation.installationId()
        );
    }
}
