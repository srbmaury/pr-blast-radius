package com.srbmaury.blastradius.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubAppAuthenticationServiceTest {

    @Test
    void createsRs256JwtWithRecommendedClientIdIssuer()
            throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();

        String pem = """
                -----BEGIN PRIVATE KEY-----
                %s
                -----END PRIVATE KEY-----
                """.formatted(
                Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(
                                pair.getPrivate().getEncoded()
                        )
        );

        var service =
                new GitHubAppAuthenticationService(
                        "Iv1.client",
                        pem
                );

        String jwt = service.createJwt();
        String[] parts = jwt.split("\\.");

        assertThat(parts).hasSize(3);

        var mapper = new ObjectMapper();
        var header = mapper.readTree(
                decode(parts[0])
        );
        var payload = mapper.readTree(
                decode(parts[1])
        );

        assertThat(header.path("alg").asText())
                .isEqualTo("RS256");
        assertThat(payload.path("iss").asText())
                .isEqualTo("Iv1.client");

        long iat = payload.path("iat").asLong();
        long exp = payload.path("exp").asLong();

        assertThat(exp - iat)
                .isBetween(500L, 600L);

        Signature verifier =
                Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(
                (parts[0] + "." + parts[1])
                        .getBytes(
                                StandardCharsets.US_ASCII
                        )
        );

        assertThat(verifier.verify(
                Base64.getUrlDecoder()
                        .decode(parts[2])
        )).isTrue();
    }

    @Test
    void parsesPkcs1PrivateKeyFormatUsedByGitHub()
            throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var privateKey =
                (RSAPrivateCrtKey) pair.getPrivate();

        String pem = """
                -----BEGIN RSA PRIVATE KEY-----
                %s
                -----END RSA PRIVATE KEY-----
                """.formatted(
                Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(
                                Der.pkcs1(privateKey)
                        )
        );

        var parsed =
                GitHubAppPrivateKeyParser.parse(pem);

        assertThat(parsed.getAlgorithm())
                .isEqualTo("RSA");
        assertThat(parsed.getEncoded())
                .isNotEmpty();
    }

    private String decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8
        );
    }

    private static final class Der {

        private static byte[] pkcs1(
                RSAPrivateCrtKey key
        ) {
            return sequence(
                    integer(java.math.BigInteger.ZERO),
                    integer(key.getModulus()),
                    integer(key.getPublicExponent()),
                    integer(key.getPrivateExponent()),
                    integer(key.getPrimeP()),
                    integer(key.getPrimeQ()),
                    integer(key.getPrimeExponentP()),
                    integer(key.getPrimeExponentQ()),
                    integer(key.getCrtCoefficient())
            );
        }

        private static byte[] integer(
                java.math.BigInteger value
        ) {
            return tagged(
                    0x02,
                    value.toByteArray()
            );
        }

        private static byte[] sequence(
                byte[]... values
        ) {
            return tagged(
                    0x30,
                    concat(values)
            );
        }

        private static byte[] tagged(
                int tag,
                byte[] value
        ) {
            return concat(
                    new byte[]{(byte) tag},
                    length(value.length),
                    value
            );
        }

        private static byte[] length(int value) {
            if (value < 128) {
                return new byte[]{(byte) value};
            }

            int bytes = value <= 0xff ? 1
                    : value <= 0xffff ? 2
                    : value <= 0xffffff ? 3
                    : 4;

            byte[] result =
                    new byte[bytes + 1];
            result[0] =
                    (byte) (0x80 | bytes);

            for (int i = bytes; i > 0; i--) {
                result[i] = (byte) value;
                value >>>= 8;
            }

            return result;
        }

        private static byte[] concat(
                byte[]... values
        ) {
            int length = 0;
            for (byte[] value : values) {
                length += value.length;
            }

            byte[] result = new byte[length];
            int offset = 0;

            for (byte[] value : values) {
                System.arraycopy(
                        value,
                        0,
                        result,
                        offset,
                        value.length
                );
                offset += value.length;
            }

            return result;
        }
    }
}
