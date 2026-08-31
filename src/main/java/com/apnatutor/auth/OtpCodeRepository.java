package com.apnatutor.auth;

import java.time.Instant;
import java.util.Optional;

import com.apnatutor.auth.domain.OtpCode;
import com.apnatutor.auth.domain.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

	/**
	 * The most recent code for a phone, whatever its state.
	 *
	 * <p>Deliberately not filtered to usable codes: requesting a new code invalidates the previous
	 * one, so only the newest may ever be verified. Filtering here would let an old code stay live
	 * after a newer one was issued.
	 */
	Optional<OtpCode> findFirstByPhoneAndPurposeOrderByIdDesc(String phone, OtpPurpose purpose);

	/** Enforces the hourly send cap (SOURCE_OF_TRUTH.md section 3.4). */
	@Query("SELECT COUNT(o) FROM OtpCode o WHERE o.phone = :phone AND o.createdAt >= :since")
	long countSentSince(@Param("phone") String phone, @Param("since") Instant since);
}
