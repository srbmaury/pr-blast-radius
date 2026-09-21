package com.srbmaury.blastradius.api;

import com.srbmaury.blastradius.catalog.RepositoryServiceCatalog;
import com.srbmaury.blastradius.domain.RepositoryServiceMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog/repositories")
public class RepositoryServiceCatalogController {

    private final RepositoryServiceCatalog catalog;

    public RepositoryServiceCatalogController(
            RepositoryServiceCatalog catalog
    ) {
        this.catalog = catalog;
    }

    @PutMapping("/{owner}/{repo}")
    public RepositoryServiceMapping put(
            @PathVariable String owner,
            @PathVariable String repo,
            @RequestBody RepositoryServiceRequest request
    ) {
        String repository = owner + "/" + repo;
        catalog.put(repository, request.service());
        return catalog.find(repository).orElseThrow();
    }

    @GetMapping("/{owner}/{repo}")
    public RepositoryServiceMapping get(
            @PathVariable String owner,
            @PathVariable String repo
    ) {
        return catalog.find(owner + "/" + repo)
                .orElseThrow(() -> new RepositoryMappingNotFoundException(
                        owner + "/" + repo
                ));
    }

    @GetMapping
    public List<RepositoryServiceMapping> all() {
        return catalog.all();
    }

    @DeleteMapping("/{owner}/{repo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String owner,
            @PathVariable String repo
    ) {
        catalog.delete(owner + "/" + repo);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class RepositoryMappingNotFoundException
            extends RuntimeException {

        RepositoryMappingNotFoundException(String repository) {
            super("No service mapping found for " + repository);
        }
    }
}
