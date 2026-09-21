package com.srbmaury.blastradius.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srbmaury.blastradius.domain.GitHubInstallCallbackResult;
import com.srbmaury.blastradius.domain.GitHubInstallStart;
import com.srbmaury.blastradius.domain.GitHubInstallationInfo;
import com.srbmaury.blastradius.github.GitHubOnboardingService;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/onboarding/github")
public class GitHubOnboardingController {

    private static final String TENANT_HEADER =
            "X-Tenant-ID";

    private final TenantAccessResolver accessResolver;
    private final GitHubOnboardingService onboardingService;
    private final ObjectMapper objectMapper;

    public GitHubOnboardingController(
            TenantAccessResolver accessResolver,
            GitHubOnboardingService onboardingService,
            ObjectMapper objectMapper
    ) {
        this.accessResolver = accessResolver;
        this.onboardingService = onboardingService;
        this.objectMapper = objectMapper;
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

    @GetMapping(
            value = "/callback",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> callback(
            @RequestParam String code,
            @RequestParam String state
    ) {
        GitHubInstallCallbackResult result =
                onboardingService.complete(
                        code,
                        state
                );

        String status = result.boundInstallationId() != null
                ? "connected"
                : result.candidates().isEmpty()
                        ? "none"
                        : "select";

        final String messageJson;
        try {
            messageJson = objectMapper.writeValueAsString(
                    Map.of(
                            "source",
                            "pr-blast-radius",
                            "type",
                            "github-install-complete",
                            "status",
                            status,
                            "tenantId",
                            result.tenantId()
                    )
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to render GitHub onboarding callback",
                    ex
            );
        }

        String title = switch (status) {
            case "connected" -> "GitHub connected";
            case "select" -> "Choose an installation";
            default -> "No installation found";
        };

        String detail = switch (status) {
            case "connected" ->
                    "The GitHub installation is connected. This window can close.";
            case "select" ->
                    "Return to PR Blast Radius to choose one of the verified installations.";
            default ->
                    "No accessible installation was found. Return to PR Blast Radius and try again.";
        };

        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body { font-family: system-ui, sans-serif; margin: 0; background: #0b0d12; color: #f5f7fb; min-height: 100vh; display: grid; place-items: center; }
                    main { max-width: 520px; padding: 32px; border: 1px solid #2a2f3a; border-radius: 18px; background: #121620; box-shadow: 0 18px 70px rgba(0,0,0,.35); }
                    h1 { margin: 0 0 12px; font-size: 24px; }
                    p { color: #aeb7c7; line-height: 1.55; }
                    a { color: #d8e2ff; }
                  </style>
                </head>
                <body>
                  <main>
                    <h1>%s</h1>
                    <p>%s</p>
                    <p><a href="/">Back to dashboard</a></p>
                  </main>
                  <script>
                    const message = %s;
                    if (window.opener && !window.opener.closed) {
                      window.opener.postMessage(message, window.location.origin);
                      setTimeout(() => window.close(), 250);
                    }
                  </script>
                </body>
                </html>
                """.formatted(
                title,
                title,
                detail,
                messageJson
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @GetMapping("/installations/candidates")
    public List<GitHubInstallationInfo> candidates(
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return onboardingService.candidates(
                accessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                )
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
