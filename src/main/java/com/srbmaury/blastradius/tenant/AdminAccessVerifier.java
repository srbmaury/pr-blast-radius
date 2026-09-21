package com.srbmaury.blastradius.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminAccessVerifier {

    private final String adminToken;

    public AdminAccessVerifier(
            @Value("${security.admin-token:}")
            String adminToken
    ) {
        this.adminToken = adminToken;
    }

    public void requireValid(String providedToken) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND
            );
        }

        if (providedToken == null
                || !MessageDigest.isEqual(
                        adminToken.getBytes(StandardCharsets.UTF_8),
                        providedToken.getBytes(StandardCharsets.UTF_8)
                )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid admin token"
            );
        }
    }
}
