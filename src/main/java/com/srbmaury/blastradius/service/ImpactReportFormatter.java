package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.ImpactAnalysisResponse;
import com.srbmaury.blastradius.domain.ImpactFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImpactReportFormatter {

    private static final int MAX_CHANGES = 20;
    private static final int MAX_FINDINGS = 30;

    public String toMarkdown(ImpactAnalysisResponse response) {
        StringBuilder out = new StringBuilder();
        out.append("## PR Blast Radius\n\n");

        appendChanges(out, response.changeSet().changes());
        appendFindings(out, response.findings());

        return out.toString().trim();
    }

    private void appendChanges(StringBuilder out, List<DetectedChange> changes) {
        out.append("**Detected changes**\n");

        if (changes.isEmpty()) {
            out.append("- None\n\n");
            return;
        }

        changes.stream()
                .limit(MAX_CHANGES)
                .forEach(change -> out.append("- ")
                        .append(change.operation())
                        .append(" ")
                        .append(change.kind())
                        .append(": ")
                        .append(change.identifier())
                        .append("\n"));

        if (changes.size() > MAX_CHANGES) {
            out.append("- ... ")
                    .append(changes.size() - MAX_CHANGES)
                    .append(" more\n");
        }

        out.append("\n");
    }

    private void appendFindings(StringBuilder out, List<ImpactFinding> findings) {
        out.append("**Production evidence**\n");

        if (findings.isEmpty()) {
            out.append("- No confirmed production impact found from connected evidence sources.\n");
            return;
        }

        findings.stream()
                .limit(MAX_FINDINGS)
                .forEach(finding -> out.append("- **")
                        .append(finding.confidence())
                        .append("** ")
                        .append(finding.component())
                        .append(" — ")
                        .append(finding.relationship())
                        .append(" — ")
                        .append(finding.evidence())
                        .append("\n"));

        if (findings.size() > MAX_FINDINGS) {
            out.append("- ... ")
                    .append(findings.size() - MAX_FINDINGS)
                    .append(" more findings\n");
        }
    }
}
