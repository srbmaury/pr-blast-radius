package com.srbmaury.blastradius.github;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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

    public synchronized boolean claim(
            String deliveryId,
            String eventName
    ) {
        Instant now = Instant.now();

        Optional<DeliveryState> existing =
                jdbcTemplate.query(
                        """
                        SELECT status, updated_at
                        FROM github_webhook_delivery
                        WHERE delivery_id = ?
                        """,
                        (rs, rowNum) ->
                                new DeliveryState(
                                        rs.getString("status"),
                                        rs.getTimestamp(
                                                "updated_at"
                                        ).toInstant()
                                ),
                        deliveryId
                ).stream().findFirst();

        if (existing.isEmpty()) {
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
        }

        DeliveryState state = existing.get();

        boolean retryable =
                "FAILED".equals(state.status())
                        || ("PROCESSING".equals(
                                state.status())
                        && state.updatedAt().isBefore(
                                now.minus(
                                        STALE_PROCESSING
                                )
                        ));

        if (!retryable) {
            return false;
        }

        jdbcTemplate.update(
                """
                UPDATE github_webhook_delivery
                SET event_name = ?,
                    status = 'PROCESSING',
                    detail = NULL,
                    updated_at = ?
                WHERE delivery_id = ?
                """,
                eventName,
                Timestamp.from(now),
                deliveryId
        );

        return true;
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

    private record DeliveryState(
            String status,
            Instant updatedAt
    ) {}
}
