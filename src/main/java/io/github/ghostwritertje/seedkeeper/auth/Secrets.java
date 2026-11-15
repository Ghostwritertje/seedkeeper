package io.github.ghostwritertje.seedkeeper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Comparing what a caller presents with a secret. */
final class Secrets {

    private Secrets() {
    }

    /**
     * Whether the two are equal, in a time that doesn't depend on where they differ or on their lengths: both are
     * hashed first, and the hashes (always the same length) are compared in constant time.
     */
    static boolean same(String presented, String secret) {
        if (presented == null || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), sha256(secret));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
