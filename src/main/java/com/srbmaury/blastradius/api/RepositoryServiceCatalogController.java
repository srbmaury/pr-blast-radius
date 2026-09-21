package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.catalog.RepositoryServiceCatalog;
import com.srbmaury.blastradius.domain.RepositoryServiceMapping;
import com.srbmaury.blastradius.tenant.TenantAccessResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog/repositories")
public class RepositoryServiceCatalogController {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final RepositoryServiceCatalog catalog;
    private final TenantAccessResolver tenantAccessResolver;

    public RepositoryServiceCatalogController(
            RepositoryServiceCatalog catalog,
            TenantAccessResolver tenantAccessResolver
    ) {
        this.catalog = catalog;
        this.tenantAccessResolver = tenantAccessResolver;
    }

    @PutMapping("/{owner}/{repo}")
    public RepositoryServiceMapping put(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestBody RepositoryServiceRequest request,
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
        String repository = owner + "/" + repo;

        catalog.put(
                tenant,
                repository,
                request.service()
        );

        return catalog.find(tenant, repository)
                .orElseThrow();
    }

    @GetMapping("/{owner}/{repo}")
    public RepositoryServiceMapping get(
            @PathVariable String owner,
            @PathVariable String repo,
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

        return catalog.find(
                        tenant,
                        owner + "/" + repo
                )
                .orElseThrow(() ->
                        new RepositoryMappingNotFoundException(
                                owner + "/" + repo
                        ));
    }

    @GetMapping
    public List<RepositoryServiceMapping> all(
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        return catalog.all(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                )
        );
    }

    @DeleteMapping("/{owner}/{repo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestHeader(
                    value = TENANT_HEADER,
                    required = false
            ) String tenantId,
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorization
    ) {
        catalog.delete(
                tenantAccessResolver.resolveApiTenant(
                        authorization,
                        tenantId
                ),
                owner + "/" + repo
        );
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class RepositoryMappingNotFoundException
            extends RuntimeException {

        RepositoryMappingNotFoundException(
                String repository
        ) {
            super(
                    "No service mapping found for "
                            + repository
            );
        }
    }
}
