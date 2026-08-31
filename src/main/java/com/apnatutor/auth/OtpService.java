package com.apnatutor.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.apnatutor.auth.domain.OtpCode;
import com.apnatutor.auth.domain.OtpPurpose;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.notification.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies one-time passcodes.
 *
 * <p>The security properties this class is responsible for:
 *
 * <ul>
 *   <li>Codes are generated with {@link SecureRandom}, never {@code Math.random()}.
 *   <li>Only a BCrypt hash is stored — a database leak yields no usable codes.
 *   <li>Comparison goes through the password encoder, which is constant-time.
 *   <li>Every failed guess is counted, so an attacker cannot get unlimited tries by abandoning
 *       attempts. Six digits is only a million possibilities; without an attempt cap it is trivially
 *       brute-forced.
 *   <li>A correct code is consumed, so it cannot be replayed.
 *   <li>Requesting a new code invalidates the old one — only the newest is ever checked.
 *   <li>Sends are rate-limited per phone per hour, which is what stops the endpoint being used to
 *       run up an SMS bill or to spam a stranger's phone.
 * </ul>
 */
@Service
public class OtpService {

	private static final Logger log = LoggerFactory.getLogger(OtpService.class);

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int CODE_DIGITS = 6;
	private static final int CODE_BOUND = 1_000_000;

	private final OtpCodeRepository otpCodes;
	private final OtpAttemptRecorder attemptRecorder;
	private final SmsSender smsSender;
	private final PasswordEncoder passwordEncoder;
	private final AppProperties properties;
	private final Clock clock;

	public OtpService(
			OtpCodeRepository otpCodes,
			OtpAttemptRecorder attemptRecorder,
			SmsSender smsSender,
			PasswordEncoder passwordEncoder,
			AppProperties properties,
			Clock clock) {
		this.otpCodes = otpCodes;
		this.attemptRecorder = attemptRecorder;
		this.smsSender = smsSender;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Generates a code, stores its hash, and sends it.
	 *
	 * @throws ApiException with {@link ErrorCode#OTP_SEND_LIMIT_EXCEEDED} if the hourly cap for this
	 *     phone is reached
	 */
	@Transactional
	public void requestCode(String canonicalPhone, OtpPurpose purpose) {
		Instant now = clock.instant();
		AppProperties.Otp config = properties.otp();

		long recentSends = otpCodes.countSentSince(canonicalPhone, now.minus(Duration.ofHours(1)));
		if (recentSends >= config.maxSendsPerHour()) {
			log.warn("OTP send limit reached for {}", PhoneNumbers.mask(canonicalPhone));
			throw new ApiException(ErrorCode.OTP_SEND_LIMIT_EXCEEDED,
					"Too many codes requested. Please try again in an hour.");
		}

		String code = generateCode();
		OtpCode otp = new OtpCode(
				canonicalPhone,
				passwordEncoder.encode(code),
				purpose,
				now.plus(config.ttl()));
		otpCodes.save(otp);

		smsSender.send(canonicalPhone,
				"%s is your ApnaTutor verification code. It expires in %d minutes. Do not share it with anyone."
						.formatted(code, config.ttl().toMinutes()));

		// The code itself is never logged. Only the stub SMS sender prints it, and only in dev.
		log.debug("OTP issued for {}", PhoneNumbers.mask(canonicalPhone));
	}

	/**
	 * Checks a submitted code and consumes it on success.
	 *
	 * <p>Only the newest code for the phone is considered — requesting a new one retires the
	 * previous.
	 *
	 * @throws ApiException with a specific {@link ErrorCode} for expired, exhausted, or wrong codes
	 */
	@Transactional
	public void verifyCode(String canonicalPhone, String submittedCode, OtpPurpose purpose) {
		Instant now = clock.instant();
		int maxAttempts = properties.otp().maxAttempts();

		Optional<OtpCode> latest =
				otpCodes.findFirstByPhoneAndPurposeOrderByIdDesc(canonicalPhone, purpose);

		if (latest.isEmpty()) {
			throw new ApiException(ErrorCode.OTP_INVALID,
					"That code is not valid. Please request a new one.");
		}

		OtpCode otp = latest.get();

		if (otp.getConsumedAt() != null) {
			throw new ApiException(ErrorCode.OTP_INVALID,
					"That code has already been used. Please request a new one.");
		}
		if (!now.isBefore(otp.getExpiresAt())) {
			throw new ApiException(ErrorCode.OTP_EXPIRED,
					"That code has expired. Please request a new one.");
		}
		if (otp.getAttempts() >= maxAttempts) {
			throw new ApiException(ErrorCode.OTP_ATTEMPTS_EXCEEDED,
					"Too many incorrect attempts. Please request a new code.");
		}

		if (!passwordEncoder.matches(submittedCode, otp.getCodeHash())) {
			// Committed in a SEPARATE transaction. The exception thrown immediately below would
			// otherwise roll this write back, the counter would never advance, and the attempt cap
			// would be decorative — see OtpAttemptRecorder.
			attemptRecorder.recordFailure(otp.getId());
			log.debug("Failed OTP attempt {} of {} for {}",
					otp.getAttempts() + 1, maxAttempts, PhoneNumbers.mask(canonicalPhone));
			throw new ApiException(ErrorCode.OTP_INVALID, "That code is not correct.");
		}

		otp.consume(now);
		otpCodes.save(otp);
	}

	/** Zero-padded so every code is exactly six digits — {@code 000042} is as valid as any other. */
	private static String generateCode() {
		return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_BOUND));
	}
}
