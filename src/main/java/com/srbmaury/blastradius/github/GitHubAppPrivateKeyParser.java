package com.srbmaury.blastradius.github;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;

public final class GitHubAppPrivateKeyParser {

    private GitHubAppPrivateKeyParser() {}

    public static PrivateKey parse(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "GitHub App private key is not configured"
            );
        }

        String normalized = pem
                .replace("\\n", "\n")
                .trim();

        try {
            if (normalized.contains(
                    "BEGIN RSA PRIVATE KEY")) {
                return parsePkcs1(normalized);
            }

            if (normalized.contains(
                    "BEGIN PRIVATE KEY")) {
                byte[] der = decodePem(
                        normalized,
                        "PRIVATE KEY"
                );

                return KeyFactory.getInstance("RSA")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(der)
                        );
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to parse GitHub App RSA private key",
                    ex
            );
        }

        throw new IllegalStateException(
                "Unsupported GitHub App private key PEM format"
        );
    }

    private static PrivateKey parsePkcs1(
            String pem
    ) throws Exception {
        byte[] der = decodePem(
                pem,
                "RSA PRIVATE KEY"
        );
        DerReader root = new DerReader(der);
        DerReader sequence = root.readSequence();

        sequence.readInteger(); // version
        BigInteger modulus = sequence.readInteger();
        BigInteger publicExponent = sequence.readInteger();
        BigInteger privateExponent = sequence.readInteger();
        BigInteger primeP = sequence.readInteger();
        BigInteger primeQ = sequence.readInteger();
        BigInteger primeExponentP = sequence.readInteger();
        BigInteger primeExponentQ = sequence.readInteger();
        BigInteger crtCoefficient = sequence.readInteger();

        return KeyFactory.getInstance("RSA")
                .generatePrivate(
                        new RSAPrivateCrtKeySpec(
                                modulus,
                                publicExponent,
                                privateExponent,
                                primeP,
                                primeQ,
                                primeExponentP,
                                primeExponentQ,
                                crtCoefficient
                        )
                );
    }

    private static byte[] decodePem(
            String pem,
            String label
    ) {
        String content = pem
                .replace(
                        "-----BEGIN " + label + "-----",
                        ""
                )
                .replace(
                        "-----END " + label + "-----",
                        ""
                )
                .replaceAll("\\s+", "");

        return Base64.getDecoder().decode(content);
    }

    private static final class DerReader {
        private final byte[] data;
        private int offset;

        private DerReader(byte[] data) {
            this.data = data;
        }

        private DerReader readSequence() {
            expect(0x30);
            int length = readLength();
            byte[] value = readBytes(length);
            return new DerReader(value);
        }

        private BigInteger readInteger() {
            expect(0x02);
            return new BigInteger(
                    readBytes(readLength())
            );
        }

        private void expect(int tag) {
            if (offset >= data.length
                    || (data[offset++] & 0xff) != tag) {
                throw new IllegalArgumentException(
                        "Invalid DER structure"
                );
            }
        }

        private int readLength() {
            if (offset >= data.length) {
                throw new IllegalArgumentException(
                        "Invalid DER length"
                );
            }

            int first = data[offset++] & 0xff;
            if ((first & 0x80) == 0) {
                return first;
            }

            int bytes = first & 0x7f;
            if (bytes < 1 || bytes > 4) {
                throw new IllegalArgumentException(
                        "Unsupported DER length"
                );
            }

            int length = 0;
            for (int i = 0; i < bytes; i++) {
                if (offset >= data.length) {
                    throw new IllegalArgumentException(
                            "Invalid DER length"
                    );
                }
                length = (length << 8)
                        | (data[offset++] & 0xff);
            }
            return length;
        }

        private byte[] readBytes(int length) {
            if (length < 0
                    || offset + length > data.length) {
                throw new IllegalArgumentException(
                        "Invalid DER value length"
                );
            }

            byte[] result = java.util.Arrays.copyOfRange(
                    data,
                    offset,
                    offset + length
            );
            offset += length;
            return result;
        }
    }
}
