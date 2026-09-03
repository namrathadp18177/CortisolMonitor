package com.example.myapplication;

import android.util.Base64;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing for the local UserProfile.db (users/care_providers tables). Uses
 * PBKDF2WithHmacSHA256 — built into the Android/JDK crypto providers, no extra dependency,
 * NIST-recommended for password storage (unlike bcrypt/scrypt/Argon2 this doesn't require
 * pulling in a third-party library, and Android's javax.crypto already implements it).
 *
 * Stored format (kept in the existing TEXT password column, so no schema/column change is
 * needed): "PBKDF2$<iterations>$<base64 salt>$<base64 hash>".
 *
 * Backward compatibility / migration: this class only knows how to hash and verify — it does
 * NOT know about legacy plaintext rows. UserDatabaseHelper's login methods check
 * {@link #isHashed} first; if a stored value doesn't look like our format, it's an old
 * plaintext row from before this change, compared directly, and silently re-hashed on a
 * successful login. This means existing accounts keep working with their existing password —
 * nobody is locked out or forced to reset — and every account is upgraded to a proper hash
 * the next time its owner logs in, with no bulk migration script required.
 */
final class PasswordHasher {

    private static final String PREFIX = "PBKDF2";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000; // OWASP 2023 minimum recommendation for PBKDF2-SHA256
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private PasswordHasher() {}

    /** Hashes a plaintext password with a freshly generated random salt. */
    static String hash(String plainPassword) {
        try {
            byte[] salt = new byte[SALT_LENGTH_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] digest = pbkdf2(plainPassword.toCharArray(), salt, ITERATIONS);
            return String.format(Locale.US, "%s$%d$%s$%s",
                    PREFIX, ITERATIONS,
                    Base64.encodeToString(salt, Base64.NO_WRAP),
                    Base64.encodeToString(digest, Base64.NO_WRAP));
        } catch (Exception e) {
            // Hashing failure means we cannot safely store this password at all — fail loudly
            // rather than silently falling back to plaintext.
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    /** True if storedValue is already in our "PBKDF2$..." format, false for a legacy
     *  plaintext row (or null/empty). */
    static boolean isHashed(String storedValue) {
        return storedValue != null && storedValue.startsWith(PREFIX + "$");
    }

    /** Verifies candidatePassword against a value produced by {@link #hash}. Only meaningful
     *  when {@link #isHashed} is true for storedValue — returns false on any malformed input
     *  rather than throwing, since a corrupt/foreign value should just fail verification. */
    static boolean verify(String candidatePassword, String storedValue) {
        if (candidatePassword == null || storedValue == null) return false;
        try {
            String[] parts = storedValue.split("\\$");
            if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.decode(parts[2], Base64.NO_WRAP);
            byte[] expected = Base64.decode(parts[3], Base64.NO_WRAP);
            byte[] actual = pbkdf2(candidatePassword.toCharArray(), salt, iterations);
            return constantTimeEquals(actual, expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        return factory.generateSecret(spec).getEncoded();
    }

    /** Avoids timing side-channels on hash comparison. */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
