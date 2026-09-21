package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.EvidenceCoverage;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ImpactAnalysisService {

    private final DatabaseImpactService databaseImpactService;
    private final RuntimeImpactService runtimeImpactService;
    private final EvidenceCoverageService evidenceCoverageService;

    public ImpactAnalysisService(
            DatabaseImpactService databaseImpactService,
            RuntimeImpactService runtimeImpactService,
            EvidenceCoverageService evidenceCoverageService
    ) {
        this.databaseImpactService = databaseImpactService;
        this.runtimeImpactService = runtimeImpactService;
        this.evidenceCoverageService = evidenceCoverageService;
    }

    public ImpactAnalysisResponse analyze(
            PullRequestChangeSet changeSet,
            String rootService
    ) {
        List<ImpactFinding> findings = new ArrayList<>();
        findings.addAll(databaseImpactService.analyze(changeSet));

        Set<String> changedEndpoints = changeSet.changes().stream()
                .filter(change -> change.kind() == ChangeKind.API_ENDPOINT)
                .map(change -> change.identifier())
                .collect(Collectors.toUnmodifiableSet());

        findings.addAll(runtimeImpactService.analyze(
                rootService,
                changedEndpoints
        ));

        List<ImpactFinding> immutableFindings = List.copyOf(findings);
        List<EvidenceCoverage> coverage = evidenceCoverageService.evaluate(
                changeSet,
                rootService,
                immutableFindings
        );

        return new ImpactAnalysisResponse(
                changeSet,
                immutableFindings,
                coverage
        );
    }
}
