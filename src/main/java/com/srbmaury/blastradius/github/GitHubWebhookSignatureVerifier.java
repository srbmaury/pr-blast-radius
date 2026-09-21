package com.srbmaury.blastradius.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class GitHubWebhookSignatureVerifier {

    private final String webhookSecret;

    public GitHubWebhookSignatureVerifier(
            @Value("${github.webhook-secret:}")
            String webhookSecret
    ) {
        this.webhookSecret = webhookSecret;
    }

    public void verify(
            byte[] payload,
            String signatureHeader
    ) {
        if (webhookSecret == null
                || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "GitHub webhook secret is not configured"
            );
        }

        if (signatureHeader == null
                || !signatureHeader.startsWith("sha256=")) {
            throw new SecurityException(
                    "GitHub webhook signature is missing"
            );
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(
                            StandardCharsets.UTF_8
                    ),
                    "HmacSHA256"
            ));

            String expected =
                    "sha256="
                            + HexFormat.of().formatHex(
                                    mac.doFinal(payload)
                            );

            if (!MessageDigest.isEqual(
                    expected.getBytes(
                            StandardCharsets.US_ASCII
                    ),
                    signatureHeader.getBytes(
                            StandardCharsets.US_ASCII
                    )
            )) {
                throw new SecurityException(
                        "GitHub webhook signature is invalid"
                );
            }
        } catch (SecurityException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to verify GitHub webhook signature",
                    ex
            );
        }
    }
}
