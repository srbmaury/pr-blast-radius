package com.srbmaury.blastradius.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

@Service
public class GitHubAppAuthenticationService {

    private final String clientId;
    private final PrivateKey privateKey;

    public GitHubAppAuthenticationService(
            @Value("${github.app-client-id:}")
            String clientId,
            @Value("${github.app-private-key:}")
            String privateKeyPem
    ) {
        this.clientId = clientId;
        this.privateKey =
                clientId == null
                        || clientId.isBlank()
                        || privateKeyPem == null
                        || privateKeyPem.isBlank()
                        ? null
                        : GitHubAppPrivateKeyParser.parse(
                                privateKeyPem
                        );
    }

    public boolean isConfigured() {
        return privateKey != null
                && clientId != null
                && !clientId.isBlank();
    }

    public String createJwt() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "GitHub App client id/private key are not configured"
            );
        }

        Instant now = Instant.now();
        long issuedAt = now.minusSeconds(60)
                .getEpochSecond();
        long expiresAt = now.plusSeconds(9 * 60)
                .getEpochSecond();

        String header =
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload =
                "{\"iat\":" + issuedAt
                        + ",\"exp\":" + expiresAt
                        + ",\"iss\":\""
                        + escapeJson(clientId)
                        + "\"}";

        String signingInput =
                base64Url(header.getBytes(
                        StandardCharsets.UTF_8
                ))
                        + "."
                        + base64Url(payload.getBytes(
                                StandardCharsets.UTF_8
                        ));

        try {
            Signature signer =
                    Signature.getInstance(
                            "SHA256withRSA"
                    );
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(
                    StandardCharsets.US_ASCII
            ));

            return signingInput
                    + "."
                    + base64Url(signer.sign());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to sign GitHub App JWT",
                    ex
            );
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value);
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
