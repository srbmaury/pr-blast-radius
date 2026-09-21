package com.srbmaury.blastradius.github;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubWebhookSignatureVerifierTest {

    @Test
    void acceptsGitHubPublishedSha256TestVector() {
        var verifier = new GitHubWebhookSignatureVerifier(
                "It's a Secret to Everybody"
        );

        assertThatCode(() -> verifier.verify(
                "Hello, World!".getBytes(
                        StandardCharsets.UTF_8
                ),
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSignature() {
        var verifier = new GitHubWebhookSignatureVerifier(
                "secret"
        );

        assertThatThrownBy(() -> verifier.verify(
                "{}".getBytes(StandardCharsets.UTF_8),
                "sha256=deadbeef"
        )).isInstanceOf(SecurityException.class);
    }
}
