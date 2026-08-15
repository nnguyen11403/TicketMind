package com.ticketmind.backend.common.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for individual column values.
 *
 * <p>Stored form is {@code v1:<base64 iv>:<base64 ciphertext||tag>}. The
 * version prefix is what makes key rotation possible without a big-bang
 * migration: a future {@code v2} can be written by new code while {@code v1}
 * values are still readable.
 *
 * <p>GCM rather than CBC because it authenticates: a row edited directly in
 * Postgres fails to decrypt rather than silently returning attacker-chosen
 * plaintext. The IV is random per value and stored alongside, which is what
 * keeps two tickets with identical bodies from producing identical ciphertext.
 */
@Component
public class FieldCipher {

	static final String PREFIX_V1 = "v1:";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;
	private static final int KEY_LENGTH_BYTES = 32;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public FieldCipher(CryptoProperties properties) {
		this.key = new SecretKeySpec(decodeKey(properties.fieldKey()), "AES");
	}

	private static byte[] decodeKey(String configured) {
		if (configured == null || configured.isBlank()) {
			throw new IllegalStateException(
					"app.crypto.field-key (APP_ENCRYPTION_KEY) is not set. Generate one with: "
							+ "openssl rand -base64 32");
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configured.trim());
		} catch (IllegalArgumentException ex) {
			throw new IllegalStateException("app.crypto.field-key must be base64-encoded", ex);
		}
		if (raw.length != KEY_LENGTH_BYTES) {
			throw new IllegalStateException(
					"app.crypto.field-key must decode to exactly 32 bytes (256 bits), got " + raw.length);
		}
		return raw;
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		byte[] iv = new byte[IV_LENGTH_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			Base64.Encoder encoder = Base64.getEncoder();
			return PREFIX_V1 + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
		} catch (GeneralSecurityException ex) {
			// Key length and transformation are both fixed and validated at
			// construction, so this is unreachable short of a broken JCE.
			throw new IllegalStateException("field encryption failed", ex);
		}
	}

	/**
	 * Decrypts a stored value, passing anything without the {@code v1:} prefix
	 * through untouched.
	 *
	 * <p>That fallback is the read half of encrypt-on-write: rows written
	 * before this converter existed stay readable and are re-encrypted the next
	 * time they are saved. It is a read path only. Writes always encrypt.
	 */
	public String decrypt(String stored) {
		if (stored == null || !stored.startsWith(PREFIX_V1)) {
			return stored;
		}
		String[] parts = stored.split(":", 3);
		if (parts.length != 3) {
			throw new IllegalStateException("malformed encrypted value: expected v1:<iv>:<ciphertext>");
		}
		try {
			Base64.Decoder decoder = Base64.getDecoder();
			byte[] iv = decoder.decode(parts[1]);
			byte[] ciphertext = decoder.decode(parts[2]);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			// Wrong key, or the row was tampered with. Both must be loud: a
			// silent fallback to the raw ciphertext would render it to the user
			// as if it were the real content.
			throw new IllegalStateException("field decryption failed", ex);
		}
	}
}
