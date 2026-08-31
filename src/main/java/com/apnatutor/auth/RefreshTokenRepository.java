package com.apnatutor.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.apnatutor.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Revokes every unrevoked token in a lineage.
	 *
	 * <p>Called when a rotated token is presented again — the sign of theft or replay. Killing the
	 * whole family logs the attacker out along with the legitimate user, which is the correct
	 * trade: the user re-authenticates with an OTP, the attacker cannot.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE RefreshToken t SET t.revokedAt = :now "
			+ "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

	/** Revokes every active token for a user — logout-everywhere, and admin suspension. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE RefreshToken t SET t.revokedAt = :now "
			+ "WHERE t.userId = :userId AND t.revokedAt IS NULL")
	int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
