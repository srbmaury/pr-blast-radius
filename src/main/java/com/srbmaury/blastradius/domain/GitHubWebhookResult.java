package com.srbmaury.blastradius.domain;

public record GitHubWebhookResult(
        String deliveryId,
        String status,
        String detail
) {}
