package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.GitHubInstallCallbackResult;
import com.srbmaury.blastradius.domain.GitHubInstallStart;
import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.github.GitHubOnboardingService;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/onboarding/github")
public class GitHubOnboardingController {

    private static final String TENANT_HEADER =
            "X-Tenant-ID";

    private final TenantAccessResolver accessResolver;
    private final GitHubOnboardingService onboardingService;

    public GitHubOnboardingController(
            TenantAccessResolver accessResolver,
            GitHubOnboardingService onboardingService
    ) {
        this.accessResolver = accessResolver;
        this.onboardingService = onboardingService;
    }

    @PostMapping("/install")
    public GitHubInstallStart start(
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = accessResolver.resolveApiTenant(
                authorization,
                tenantId
        );

        return onboardingService.start(tenant);
    }

    @GetMapping("/callback")
    public GitHubInstallCallbackResult callback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        return onboardingService.complete(
                code,
                state
        );
    }

    @PostMapping("/installations/{installationId}/claim")
    public GitHubInstallationInfo claim(
            @PathVariable long installationId,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        String tenant = accessResolver.resolveApiTenant(
                authorization,
                tenantId
        );

        return onboardingService.claim(
                tenant,
                installationId
        );
    }

    @GetMapping("/installations")
    public List<GitHubInstallationInfo> installations(
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return onboardingService.installations(
                accessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                )
        );
    }
}
