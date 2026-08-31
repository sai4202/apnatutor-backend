package com.apnatutor.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import com.apnatutor.auth.domain.RefreshToken;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues access tokens and manages refresh-token rotation.
 *
 * <p><strong>Access tokens</strong> are short-lived (15 minutes) HS256 JWTs. They are not stored:
 * they are verified by signature, which is what makes them cheap. The cost of that choice is that
 * an access token cannot be revoked mid-life — hence the short lifetime.
 *
 * <p><strong>Refresh tokens</strong> are long-lived (30 days) opaque random values, stored as
 * SHA-256 hashes. Every use rotates them: the presented token is revoked and a replacement issued in
 * the same family.
 *
 * <p><strong>Reuse detection.</strong> If an already-rotated token is presented again, either it was
 * stolen or replayed. We cannot tell the attacker from the victim, so the entire family is revoked.
 * The legitimate user re-authenticates with an OTP; the attacker cannot. Without this, a stolen
 * refresh token grants indefinite access and nothing ever notices.
 */
@Service
public class TokenService {

	private static final Logger log = LoggerFactory.getLogger(TokenService.class);

	private static final SecureRandom RANDOM = new SecureRandom();
	/** 256 bits of entropy — nothing to brute-force, which is why SHA-256 storage is sufficient. */
	private static final int REFRESH_TOKEN_BYTES = 32;

	private static final String ISSUER = "apnatutor";
	private static final String ROLE_CLAIM = "role";

	private final JwtEncoder jwtEncoder;
	private final RefreshTokenRepository refreshTokens;
	private final TokenFamilyRevoker familyRevoker;
	private final AppProperties properties;
	private final Clock clock;

	public TokenService(
			JwtEncoder jwtEncoder,
			RefreshTokenRepository refreshTokens,
			TokenFamilyRevoker familyRevoker,
			AppProperties properties,
			Clock clock) {
		this.jwtEncoder = jwtEncoder;
		this.refreshTokens = refreshTokens;
		this.familyRevoker = familyRevoker;
		this.properties = properties;
		this.clock = clock;
	}

	/** An access token plus the raw refresh token. The raw refresh value exists only here and in the cookie. */
	public record TokenPair(String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {
	}

	/** Starts a new session — a fresh token family. */
	@Transactional
	public TokenPair issueNewSession(User user) {
		String rawRefresh = generateRefreshToken();
		Instant expiresAt = clock.instant().plus(properties.jwt().refreshTokenTtl());

		refreshTokens.save(RefreshToken.startFamily(user.getId(), sha256(rawRefresh), expiresAt));

		return new TokenPair(
				createAccessToken(user),
				rawRefresh,
				properties.jwt().accessTokenTtl().toSeconds());
	}

	/**
	 * Exchanges a refresh token for a new pair, rotating it.
	 *
	 * @throws ApiException with {@link ErrorCode#REFRESH_TOKEN_INVALID} for an unknown, expired, or
	 *     already-used token
	 */
	@Transactional
	public TokenPair rotate(String presentedRefreshToken, User user) {
		Instant now = clock.instant();

		RefreshToken stored = refreshTokens.findByTokenHash(sha256(presentedRefreshToken))
				.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
						"Session expired. Please sign in again."));

		if (stored.getRevokedAt() != null) {
			// A revoked token being presented means the value leaked: the legitimate client would
			// have moved on to its replacement. Burn the whole lineage.
			//
			// Committed in a SEPARATE transaction. The exception below would otherwise roll the
			// revocation back, leaving the stolen token working until it expired — reuse detection
			// that detects and then forgets. See TokenFamilyRevoker.
			log.warn("Refresh token reuse detected for user {}", stored.getUserId());
			familyRevoker.revokeFamily(stored.getFamilyId());
			throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
					"Session expired. Please sign in again.");
		}

		if (!stored.isActive(now)) {
			throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
					"Session expired. Please sign in again.");
		}

		String rawRefresh = generateRefreshToken();
		stored.revoke(now);
		refreshTokens.save(stored);
		refreshTokens.save(stored.rotate(
				sha256(rawRefresh), now.plus(properties.jwt().refreshTokenTtl())));

		return new TokenPair(
				createAccessToken(user),
				rawRefresh,
				properties.jwt().accessTokenTtl().toSeconds());
	}

	/** Ends one session. Other devices stay signed in. */
	@Transactional
	public void revoke(String presentedRefreshToken) {
		refreshTokens.findByTokenHash(sha256(presentedRefreshToken)).ifPresent(token -> {
			token.revoke(clock.instant());
			refreshTokens.save(token);
		});
	}

	/** Ends every session for a user — logout-everywhere, and admin suspension. */
	@Transactional
	public int revokeAllSessions(Long userId) {
		return refreshTokens.revokeAllForUser(userId, clock.instant());
	}

	/** Looks up the owner of a refresh token without validating it — the caller must still rotate. */
	@Transactional(readOnly = true)
	public Long findUserIdForRefreshToken(String presentedRefreshToken) {
		return refreshTokens.findByTokenHash(sha256(presentedRefreshToken))
				.map(RefreshToken::getUserId)
				.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
						"Session expired. Please sign in again."));
	}

	private String createAccessToken(User user) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.issuedAt(now)
				.expiresAt(now.plus(properties.jwt().accessTokenTtl()))
				.subject(String.valueOf(user.getId()))
				.claim(ROLE_CLAIM, user.getRole().name())
				.build();

		return jwtEncoder
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	/** URL-safe so the value can travel in a cookie without encoding surprises. */
	private static String generateRefreshToken() {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated by the JDK spec; unreachable in practice.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
