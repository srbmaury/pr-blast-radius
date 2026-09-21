package com.srbmaury.blastradius.tenant;

import com.srbmaury.blastradius.domain.TenantCredentialsIssued;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
public class TenantTokenService {

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private final TenantCredentialStore store;

    public TenantTokenService(
            TenantCredentialStore store
    ) {
        this.store = store;
    }

    public TenantCredentialsIssued provision(
            String tenantId
    ) {
        String tenant = TenantIds.normalize(tenantId);
        String apiToken = newToken("br_api_");
        String ingestToken = newToken("br_ingest_");

        store.put(
                tenant,
                hash(apiToken),
                hash(ingestToken)
        );

        return new TenantCredentialsIssued(
                tenant,
                apiToken,
                ingestToken
        );
    }

    public Optional<String> tenantForApiToken(
            String token
    ) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return store.findTenantByApiHash(
                hash(token.trim())
        );
    }

    public Optional<String> tenantForIngestToken(
            String token
    ) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        return store.findTenantByIngestHash(
                hash(token.trim())
        );
    }

    private String newToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        return prefix + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hashed = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
            );

            return java.util.HexFormat.of()
                    .formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    ex
            );
        }
    }
}
