package com.apnatutor.common.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated access to the {@code apnatutor.*} configuration.
 *
 * <p>Validated at startup so a misconfiguration fails immediately and loudly, rather than surfacing
 * as a broken login weeks later. A JWT secret that is missing or too short is the case that matters
 * most: a short HMAC key is brute-forceable, and the failure is silent.
 *
 * <p>The authoritative values for the business numbers live in {@code docs/SOURCE_OF_TRUTH.md}
 * §3.4 — this only reads them.
 */
@ConfigurationProperties(prefix = "apnatutor")
@Validated
public record AppProperties(
		@NotBlank String frontendUrl,
		@Valid @NotNull Jwt jwt,
		@Valid @NotNull Otp otp,
		@Valid @NotNull Sms sms,
		@Valid @NotNull Dev dev) {

	public record Jwt(
			@NotBlank String secret,
			@NotNull Duration accessTokenTtl,
			@NotNull Duration refreshTokenTtl) {

		/**
		 * HS256 needs a key of at least 256 bits. Anything shorter is rejected outright rather than
		 * padded, because padding a weak secret hides the weakness instead of fixing it.
		 */
		public Jwt {
			if (secret != null && secret.getBytes().length < 32) {
				throw new IllegalStateException(
						"apnatutor.jwt.secret must be at least 32 bytes for HS256. "
								+ "Generate one with: node -e \"console.log("
								+ "require('crypto').randomBytes(48).toString('base64url'))\"");
			}
		}
	}

	public record Otp(
			@NotNull Duration ttl,
			@Min(1) int maxAttempts,
			@Min(1) int maxSendsPerHour) {
	}

	public record Sms(@NotBlank String provider) {

		/** True when SMS is only being logged, never actually sent. */
		public boolean isConsoleStub() {
			return "console".equalsIgnoreCase(provider);
		}
	}

	/**
	 * Local development conveniences. <strong>Every one of these is a security hole in
	 * production.</strong>
	 *
	 * <p>Deliberately a single switch rather than three independent flags: one thing to turn off is
	 * one thing to forget to turn off, and {@link DevModeGuard} refuses to start the application if
	 * this is enabled alongside a real SMS provider.
	 *
	 * <p>What it enables:
	 *
	 * <ul>
	 *   <li>Seeded test accounts for each role, so you can sign in as a student, tutor or admin
	 *       without any SMS provider at all.
	 *   <li>A fixed OTP for those accounts, so their code never has to be read from a log.
	 *   <li>Returning the generated OTP in the API response, so the login screen can display it.
	 * </ul>
	 */
	public record Dev(
			boolean enabled,
			/** The fixed code accepted for seeded test accounts. Six digits. */
			@Pattern(regexp = "\\d{6}", message = "dev.test-account-code must be 6 digits")
			String testAccountCode) {
	}
}
