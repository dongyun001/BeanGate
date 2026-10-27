package io.beangate.crypto;

import io.beangate.config.BeanGateProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Key hashing and secret encryption.
 *
 * <p>API keys are high-entropy random strings, so a fast keyed hash (HMAC-SHA256) is the right tool:
 * it is safe against offline brute force at 256 bits of entropy and can be indexed for O(1) lookup.
 * BCrypt/Argon2 are for low-entropy human passwords and cannot be looked up by hash.
 *
 * <p>Provider credentials are encrypted with AES-GCM (random 96-bit IV per encryption).
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int SECRET_BYTES = 32;

    private final byte[] hmacKey;
    private final SecretKeySpec aesKey;

    public CryptoService(BeanGateProperties properties) {
        String pepper = properties.security().keyPepper();
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("beangate.security.key-pepper must be configured");
        }
        this.hmacKey = pepper.getBytes(StandardCharsets.UTF_8);

        String configured = properties.security().encryptionKey();
        byte[] rawKey;
        if (configured == null || configured.isBlank()) {
            log.warn("BEANGATE_ENCRYPTION_KEY is not set; using an INSECURE development key. "
                    + "Set a base64-encoded 32-byte key before running anywhere real.");
            rawKey = sha256("beangate-insecure-development-encryption-key");
        } else {
            rawKey = Base64.getDecoder().decode(configured);
            if (rawKey.length != 32) {
                throw new IllegalStateException("BEANGATE_ENCRYPTION_KEY must decode to exactly 32 bytes");
            }
        }
        this.aesKey = new SecretKeySpec(rawKey, "AES");
    }

    /** Deterministic keyed hash, hex-encoded. Used as the unique lookup column for API keys. */
    public String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** @return base64 of {@code iv || ciphertext||tag}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    /** 32 bytes of URL-safe randomness, used for both API keys and admin-visible secrets. */
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
