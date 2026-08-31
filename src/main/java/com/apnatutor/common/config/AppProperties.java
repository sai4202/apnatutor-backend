package com.apnatutor.common.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
		@Valid @NotNull Otp otp) {

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
}
