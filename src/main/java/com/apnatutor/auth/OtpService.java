package com.apnatutor.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.apnatutor.auth.domain.OtpCode;
import com.apnatutor.auth.domain.OtpPurpose;
import com.apnatutor.common.config.AppProperties;
import com.apnatutor.common.config.DevAccountSeeder;
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
	 * The result of issuing a code.
	 *
	 * @param exposedCode the code itself, but <strong>only</strong> when dev mode is on. Null in
	 *     every other configuration, so there is no path by which a real deployment can return a
	 *     live OTP to an unauthenticated caller.
	 */
	public record IssuedCode(String exposedCode) {
	}

	/**
	 * Generates a code, stores its hash, and sends it.
	 *
	 * @throws ApiException with {@link ErrorCode#OTP_SEND_LIMIT_EXCEEDED} if the hourly cap for this
	 *     phone is reached
	 */
	@Transactional
	public IssuedCode requestCode(String canonicalPhone, OtpPurpose purpose) {
		Instant now = clock.instant();
		AppProperties.Otp config = properties.otp();
		boolean devMode = properties.dev().enabled();
		boolean devAccount = devMode && DevAccountSeeder.isDevAccount(canonicalPhone);

		// Seeded test accounts skip the hourly cap. They exist to be signed into repeatedly while
		// developing, and hitting a rate limit on the demo account is pure friction with no
		// security value — the code for these is published anyway.
		if (!devAccount) {
			long recentSends = otpCodes.countSentSince(canonicalPhone, now.minus(Duration.ofHours(1)));
			if (recentSends >= config.maxSendsPerHour()) {
				log.warn("OTP send limit reached for {}", PhoneNumbers.mask(canonicalPhone));
				throw new ApiException(ErrorCode.OTP_SEND_LIMIT_EXCEEDED,
						"Too many codes requested. Please try again in an hour.");
			}
		}

		// A fixed code for test accounts, random for everyone else. The fixed value still goes
		// through the same hashing and verification path, so the flow being exercised is the real
		// one rather than a bypass.
		String code = devAccount ? properties.dev().testAccountCode() : generateCode();

		OtpCode otp = new OtpCode(
				canonicalPhone,
				passwordEncoder.encode(code),
				purpose,
				now.plus(config.ttl()));
		otpCodes.save(otp);

		if (!devAccount) {
			smsSender.send(canonicalPhone,
					"%s is your ApnaTutor verification code. It expires in %d minutes. Do not share it with anyone."
							.formatted(code, config.ttl().toMinutes()));
		}

		// The code itself is never logged. Only the stub SMS sender prints it, and only in dev.
		log.debug("OTP issued for {}", PhoneNumbers.mask(canonicalPhone));

		return new IssuedCode(devMode ? code : null);
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
