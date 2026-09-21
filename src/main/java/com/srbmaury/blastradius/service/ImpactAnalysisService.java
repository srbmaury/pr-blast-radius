package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImpactAnalysisService {

    private final DatabaseImpactService databaseImpactService;
    private final RuntimeImpactService runtimeImpactService;

    public ImpactAnalysisService(
            DatabaseImpactService databaseImpactService,
            RuntimeImpactService runtimeImpactService
    ) {
        this.databaseImpactService = databaseImpactService;
        this.runtimeImpactService = runtimeImpactService;
    }

    public ImpactAnalysisResponse analyze(
            PullRequestChangeSet changeSet,
            String rootService
    ) {
        List<ImpactFinding> findings = new ArrayList<>();
        findings.addAll(databaseImpactService.analyze(changeSet));
        findings.addAll(runtimeImpactService.analyze(rootService));

        return new ImpactAnalysisResponse(
                changeSet,
                List.copyOf(findings)
        );
    }
}
