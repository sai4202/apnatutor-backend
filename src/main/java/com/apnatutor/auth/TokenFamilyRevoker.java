package com.apnatutor.auth;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a refresh-token family in its own transaction.
 *
 * <p><strong>Why this exists — the same trap as {@link OtpAttemptRecorder}.</strong> Detecting a
 * stolen token requires two things that fight each other in one transaction: persist the revocation,
 * then reject the request. The rejection throws a {@code RuntimeException}, the transaction is
 * marked rollback-only, and the revocation is discarded. Reuse detection would log a stern warning
 * and change nothing — the stolen token would keep working until it expired.
 *
 * <p>{@code REQUIRES_NEW} commits the revocation independently of the rejection that follows.
 *
 * <p>This is a general hazard in a service layer that both writes and throws. Any time a security
 * decision must survive the exception that reports it, the write belongs in its own transaction.
 */
@Component
public class TokenFamilyRevoker {

	private static final Logger log = LoggerFactory.getLogger(TokenFamilyRevoker.class);

	private final RefreshTokenRepository refreshTokens;
	private final Clock clock;

	public TokenFamilyRevoker(RefreshTokenRepository refreshTokens, Clock clock) {
		this.refreshTokens = refreshTokens;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void revokeFamily(UUID familyId) {
		int revoked = refreshTokens.revokeFamily(familyId, clock.instant());
		log.warn("Revoked {} refresh token(s) in family {} after reuse detection", revoked, familyId);
	}
}
