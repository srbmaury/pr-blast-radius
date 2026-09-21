package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.StaticOutboundCall;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StaticOutboundImpactService {

    public List<ImpactFinding> fuse(
            List<ImpactFinding> runtimeFindings,
            List<StaticOutboundCall> staticCalls
    ) {
        List<ImpactFinding> result = new ArrayList<>(
                runtimeFindings == null
                        ? List.of()
                        : runtimeFindings
        );

        if (staticCalls == null || staticCalls.isEmpty()) {
            return List.copyOf(result);
        }

        for (StaticOutboundCall call : staticCalls) {
            int match = findMatchingRuntimeFinding(
                    result,
                    call
            );

            String staticEvidence = "static "
                    + call.clientKind()
                    + " call from "
                    + call.sourceMethod()
                    + ": "
                    + call.evidence();

            if (match < 0) {
                result.add(new ImpactFinding(
                        call.targetService(),
                        call.sourceMethod()
                                + " -> "
                                + call.targetService()
                                + " ["
                                + call.endpoint()
                                + "] (static outbound dependency)",
                        staticEvidence,
                        ImpactConfidence.STRONG
                ));
                continue;
            }

            ImpactFinding existing = result.get(match);

            if (existing.confidence() == ImpactConfidence.CONFIRMED) {
                result.set(match, new ImpactFinding(
                        existing.component(),
                        existing.relationship(),
                        existing.evidence()
                                + "; "
                                + staticEvidence,
                        ImpactConfidence.CONFIRMED
                ));
                continue;
            }

            result.set(match, new ImpactFinding(
                    existing.component(),
                    existing.relationship(),
                    existing.evidence()
                            + "; "
                            + staticEvidence,
                    ImpactConfidence.STRONG
            ));
        }

        return List.copyOf(result);
    }

    private int findMatchingRuntimeFinding(
            List<ImpactFinding> findings,
            StaticOutboundCall call
    ) {
        String endpointMarker = "["
                + call.endpoint()
                + "]";

        for (int i = 0; i < findings.size(); i++) {
            ImpactFinding finding = findings.get(i);

            if (!call.targetService().equals(
                    finding.component()
            )) {
                continue;
            }

            if (finding.relationship() != null
                    && finding.relationship()
                            .contains(endpointMarker)) {
                return i;
            }
        }

        return -1;
    }
}
