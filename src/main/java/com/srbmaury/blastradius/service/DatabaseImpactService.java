package com.srbmaury.blastradius.service;

import com.srbmaury.blastradius.domain.ChangeKind;
import com.srbmaury.blastradius.domain.DetectedChange;
import com.srbmaury.blastradius.domain.ImpactConfidence;
import com.srbmaury.blastradius.domain.ImpactFinding;
import com.srbmaury.blastradius.domain.PullRequestChangeSet;
import com.srbmaury.blastradius.postgres.PostgresDependencyCollector;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DatabaseImpactService {

    private final PostgresDependencyCollector collector;

    public DatabaseImpactService(PostgresDependencyCollector collector) {
        this.collector = collector;
    }

    public List<ImpactFinding> analyze(PullRequestChangeSet changeSet) {
        List<ImpactFinding> findings = new ArrayList<>();

        for (DetectedChange change : changeSet.changes()) {
            if (change.kind() != ChangeKind.DATABASE_TABLE
                    && change.kind() != ChangeKind.DATABASE_COLUMN) {
                continue;
            }

            DatabaseTarget target = toTarget(change);
            collector.findQueryUsage(target.table(), target.column())
                    .forEach(evidence -> findings.add(new ImpactFinding(
                            "PostgreSQL",
                            "runtime query uses " + target.displayName(),
                            "calls=" + evidence.calls()
                                    + ", rows=" + evidence.rows()
                                    + ", totalExecMs=" + Math.round(evidence.totalExecutionTimeMs())
                                    + ", query=" + evidence.query(),
                            ImpactConfidence.CONFIRMED
                    )));
        }

        return findings;
    }

    private DatabaseTarget toTarget(DetectedChange change) {
        String identifier = change.identifier();
        if (change.kind() == ChangeKind.DATABASE_TABLE) {
            return new DatabaseTarget(identifier, null, identifier);
        }

        String leftSide = identifier.contains(" -> ")
                ? identifier.substring(0, identifier.indexOf(" -> "))
                : identifier;

        int dot = leftSide.lastIndexOf('.');
        if (dot < 0) {
            return new DatabaseTarget(leftSide, null, leftSide);
        }

        String table = leftSide.substring(0, dot);
        String column = leftSide.substring(dot + 1);
        return new DatabaseTarget(table, column, leftSide);
    }

    private record DatabaseTarget(String table, String column, String displayName) {}
}
