package com.srbmaury.blastradius.github;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
public class GitHubWebhookDeliveryStore {

    private static final Duration STALE_PROCESSING =
            Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;

    public GitHubWebhookDeliveryStore(
            @Qualifier("metadataJdbcTemplate")
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS github_webhook_delivery (
                    delivery_id VARCHAR(128) PRIMARY KEY,
                    event_name VARCHAR(128) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    detail VARCHAR(2048),
                    updated_at TIMESTAMP NOT NULL
                )
                """);
    }

    public boolean claim(
            String deliveryId,
            String eventName
    ) {
        Instant now = Instant.now();

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO github_webhook_delivery (
                        delivery_id,
                        event_name,
                        status,
                        detail,
                        updated_at
                    ) VALUES (?, ?, 'PROCESSING', NULL, ?)
                    """,
                    deliveryId,
                    eventName,
                    Timestamp.from(now)
            );
            return true;
        } catch (DataAccessException duplicate) {
            // Existing delivery: only failed or stale work
            // may atomically transition back to PROCESSING.
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE github_webhook_delivery
                SET event_name = ?,
                    status = 'PROCESSING',
                    detail = NULL,
                    updated_at = ?
                WHERE delivery_id = ?
                  AND (
                        status = 'FAILED'
                        OR (
                            status = 'PROCESSING'
                            AND updated_at < ?
                        )
                  )
                """,
                eventName,
                Timestamp.from(now),
                deliveryId,
                Timestamp.from(
                        now.minus(STALE_PROCESSING)
                )
        );

        return updated == 1;
    }

    public void complete(
            String deliveryId,
            String detail
    ) {
        update(
                deliveryId,
                "COMPLETED",
                detail
        );
    }

    public void fail(
            String deliveryId,
            String detail
    ) {
        update(
                deliveryId,
                "FAILED",
                detail
        );
    }

    private void update(
            String deliveryId,
            String status,
            String detail
    ) {
        String safeDetail = detail == null
                ? null
                : detail.substring(
                        0,
                        Math.min(detail.length(), 2048)
                );

        jdbcTemplate.update(
                """
                UPDATE github_webhook_delivery
                SET status = ?,
                    detail = ?,
                    updated_at = ?
                WHERE delivery_id = ?
                """,
                status,
                safeDetail,
                Timestamp.from(Instant.now()),
                deliveryId
        );
    }
}
