package com.apnatutor.billing.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing and comparison, shared by every signature Razorpay uses.
 *
 * <p>Package-private on purpose: signature verification belongs to the gateway and nothing else
 * should be reaching for it.
 */
final class Signatures {

	private static final String ALGORITHM = "HmacSHA256";
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private Signatures() {
	}

	/** HMAC-SHA256 of {@code payload} under {@code secret}, lower-case hex — Razorpay's format. */
	static String hmacHex(String payload, String secret) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			// Only reachable if the JVM lacks HmacSHA256, which is not a recoverable state.
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	/**
	 * Constant-time comparison.
	 *
	 * <p>{@code String.equals} returns as soon as two characters differ, so the time it takes leaks
	 * how many leading characters were correct. Over enough requests that is enough to reconstruct a
	 * valid signature one character at a time. {@link MessageDigest#isEqual} takes the same time
	 * whatever the inputs.
	 */
	static boolean matches(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	private static String toHex(byte[] bytes) {
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
			out[i * 2 + 1] = HEX[bytes[i] & 0xF];
		}
		return new String(out);
	}
}
