package com.apnatutor.auth;

import com.apnatutor.auth.domain.OtpCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a failed OTP attempt in its own transaction.
 *
 * <p><strong>Why this exists.</strong> Recording a failed attempt and then rejecting the request are
 * contradictory demands on one transaction: the rejection throws a {@code RuntimeException}, which
 * marks the transaction rollback-only, which discards the increment we just made. The attempt
 * counter would never advance, the cap would never trigger, and a six-digit code — one million
 * possibilities — would be brute-forceable at whatever rate the network allows.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this write independently, so
 * the count survives the rejection that follows. A separate bean is required because Spring's
 * proxying means a self-invocation would not apply the new propagation at all.
 *
 * <p>Committing before the outer transaction resolves is the correct trade here: over-counting an
 * attempt is harmless, while under-counting removes the only defence against brute force.
 */
@Component
public class OtpAttemptRecorder {

	private final OtpCodeRepository otpCodes;

	public OtpAttemptRecorder(OtpCodeRepository otpCodes) {
		this.otpCodes = otpCodes;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(Long otpCodeId) {
		otpCodes.findById(otpCodeId).ifPresent(otp -> {
			otp.recordFailedAttempt();
			otpCodes.save(otp);
		});
	}
}
