package com.srbmaury.blastradius.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TenantAccessResolver {

    private final TenantTokenService tokenService;
    private final boolean authEnabled;

    public TenantAccessResolver(
            TenantTokenService tokenService,
            @Value("${security.tenant-auth-enabled:false}")
            boolean authEnabled
    ) {
        this.tokenService = tokenService;
        this.authEnabled = authEnabled;
    }

    public String resolveApiTenant(
            String authorizationHeader,
            String requestedTenantId
    ) {
        return resolve(
                authorizationHeader,
                requestedTenantId,
                true
        );
    }

    public String resolveIngestTenant(
            String authorizationHeader,
            String requestedTenantId
    ) {
        return resolve(
                authorizationHeader,
                requestedTenantId,
                false
        );
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    private String resolve(
            String authorizationHeader,
            String requestedTenantId,
            boolean apiToken
    ) {
        if (!authEnabled) {
            return TenantIds.normalize(
                    requestedTenantId
            );
        }

        String token = bearerToken(
                authorizationHeader
        );

        String tenant = (apiToken
                ? tokenService.tenantForApiToken(token)
                : tokenService.tenantForIngestToken(token))
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED,
                                "Invalid tenant token"
                        ));

        if (requestedTenantId != null
                && !requestedTenantId.isBlank()
                && !tenant.equals(
                        TenantIds.normalize(requestedTenantId)
                )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tenant header does not match token"
            );
        }

        return tenant;
    }

    private String bearerToken(
            String authorizationHeader
    ) {
        if (authorizationHeader == null
                || authorizationHeader.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Bearer token is required"
            );
        }

        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(
                true,
                0,
                prefix,
                0,
                prefix.length()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authorization must use Bearer"
            );
        }

        String token = authorizationHeader
                .substring(prefix.length())
                .trim();

        if (token.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Bearer token is required"
            );
        }

        return token;
    }
}
