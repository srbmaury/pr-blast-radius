package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.domain.TenantCredentialsIssued;
import com.srbmaury.blastradius.tenant.AdminAccessVerifier;
import com.srbmaury.blastradius.tenant.TenantTokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantProvisioningController {

    private static final String ADMIN_HEADER =
            "X-Admin-Token";

    private final TenantTokenService tokenService;
    private final AdminAccessVerifier adminAccessVerifier;

    public TenantProvisioningController(
            TenantTokenService tokenService,
            AdminAccessVerifier adminAccessVerifier
    ) {
        this.tokenService = tokenService;
        this.adminAccessVerifier = adminAccessVerifier;
    }

    @PostMapping
    public TenantCredentialsIssued provision(
            @RequestBody TenantProvisionRequest request,
            @RequestHeader(
                    value = ADMIN_HEADER,
                    required = false
            ) String adminToken
    ) {
        adminAccessVerifier.requireValid(adminToken);

        return tokenService.provision(
                request.tenantId()
        );
    }
}
