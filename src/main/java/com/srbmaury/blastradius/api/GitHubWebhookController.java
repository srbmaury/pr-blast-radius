package com.srbmaury.blastradius.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.srbmaury.blastradius.domain.GitHubWebhookResult;
import com.srbmaury.blastradius.github.GitHubWebhookService;
import com.srbmaury.blastradius.github.GitHubWebhookSignatureVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/github")
public class GitHubWebhookController {

    private final GitHubWebhookSignatureVerifier
            signatureVerifier;
    private final GitHubWebhookService webhookService;
    private final ObjectMapper objectMapper;

    public GitHubWebhookController(
            GitHubWebhookSignatureVerifier
                    signatureVerifier,
            GitHubWebhookService webhookService,
            ObjectMapper objectMapper
    ) {
        this.signatureVerifier = signatureVerifier;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public GitHubWebhookResult receive(
            @RequestBody byte[] payload,
            @RequestHeader("X-Hub-Signature-256")
            String signature,
            @RequestHeader("X-GitHub-Event")
            String eventName,
            @RequestHeader("X-GitHub-Delivery")
            String deliveryId
    ) {
        try {
            signatureVerifier.verify(
                    payload,
                    signature
            );
        } catch (SecurityException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid GitHub webhook signature"
            );
        }

        final JsonNode body;
        try {
            body = objectMapper.readTree(payload);
        } catch (IOException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid GitHub webhook payload",
                    ex
            );
        }

        return webhookService.handle(
                deliveryId,
                eventName,
                body
        );
    }
}
