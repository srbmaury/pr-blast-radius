package com.srbmaury.blastradius.postgres;

import com.srbmaury.blastradius.domain.PostgresQueryEvidence;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class PostgresDependencyCollector {

    private static final int QUERY_LIMIT = 100;

    private final JdbcTemplate jdbcTemplate;

    public PostgresDependencyCollector(
            @Qualifier("customerJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAvailable() {
        try {
            Boolean installed = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')",
                    Boolean.class
            );
            return Boolean.TRUE.equals(installed);
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public List<PostgresQueryEvidence> findQueryUsage(String table, String column) {
        if (!isAvailable()) {
            return List.of();
        }

        String tableName = simpleIdentifier(table);

        List<PostgresQueryEvidence> candidates = jdbcTemplate.query(
                """
                SELECT query, calls, rows, total_exec_time
                FROM pg_stat_statements
                WHERE query ILIKE ?
                ORDER BY calls DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new PostgresQueryEvidence(
                        rs.getString("query"),
                        rs.getLong("calls"),
                        rs.getLong("rows"),
                        rs.getDouble("total_exec_time")
                ),
                "%" + tableName + "%",
                QUERY_LIMIT
        );

        return candidates.stream()
                .filter(evidence -> containsIdentifier(evidence.query(), tableName))
                .filter(evidence -> column == null
                        || containsIdentifier(evidence.query(), simpleIdentifier(column)))
                .toList();
    }

    private boolean containsIdentifier(String query, String identifier) {
        if (query == null || identifier == null || identifier.isBlank()) {
            return false;
        }

        Pattern pattern = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_$])"
                        + Pattern.quote(identifier)
                        + "(?![A-Za-z0-9_$])"
        );
        return pattern.matcher(query).find();
    }

    private String simpleIdentifier(String identifier) {
        String normalized = identifier
                .replace("\"", "")
                .toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
