package com.apnatutor.auth;

import java.time.Clock;
import java.util.Optional;

import com.apnatutor.auth.domain.OtpPurpose;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates sign-in and registration.
 *
 * <p>Login and registration are the same flow deliberately: the user enters a phone, gets a code,
 * and enters it. Whether an account already existed is an implementation detail they should never
 * have to think about — and one we must not disclose.
 *
 * <p><strong>Enumeration defence (M1-03.7).</strong> Requesting a code behaves identically for a
 * registered and an unregistered number: same response, same status, same timing characteristics.
 * If it did not, this endpoint would be a free oracle for testing which phone numbers hold accounts
 * — worth real money to a competitor and to anyone assembling a list to spam.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final UserRepository users;
	private final OtpService otpService;
	private final TokenService tokenService;
	private final Clock clock;

	public AuthService(
			UserRepository users,
			OtpService otpService,
			TokenService tokenService,
			Clock clock) {
		this.users = users;
		this.otpService = otpService;
		this.tokenService = tokenService;
		this.clock = clock;
	}

	/** The outcome of a successful verification. */
	public record AuthResult(User user, TokenService.TokenPair tokens, boolean newAccount) {
	}

	/**
	 * Sends a login code.
	 *
	 * <p>Gives the caller nothing that distinguishes a known phone from an unknown one.
	 *
	 * @return the code itself when dev mode is on, so the login screen can display it; otherwise a
	 *     result carrying null
	 */
	@Transactional
	public OtpService.IssuedCode requestLoginCode(String rawPhone) {
		String phone = normaliseOrReject(rawPhone);
		return otpService.requestCode(phone, OtpPurpose.AUTH);
	}

	/**
	 * Verifies a code and returns tokens, creating the account on first successful verification.
	 *
	 * @param requestedRole role to use if this creates an account; ignored for an existing one
	 */
	@Transactional
	public AuthResult verifyAndAuthenticate(String rawPhone, String code, UserRole requestedRole) {
		String phone = normaliseOrReject(rawPhone);

		// Throws on a bad, expired or exhausted code. Nothing below runs unless the phone is proven.
		otpService.verifyCode(phone, code, OtpPurpose.AUTH);

		Optional<User> existing = users.findByPhone(phone);
		boolean newAccount = existing.isEmpty();

		User user = existing.orElseGet(() -> {
			if (requestedRole == null) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"Choose whether you are signing up as a student or a tutor.");
			}
			if (requestedRole == UserRole.ADMIN) {
				// Admin accounts are provisioned deliberately, never self-served. Without this
				// check, anyone with a phone could mint themselves an admin account.
				log.warn("Rejected attempt to self-register as ADMIN from {}",
						PhoneNumbers.mask(phone));
				throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid role.");
			}
			return users.save(User.registerVerified(phone, requestedRole, clock.instant()));
		});

		if (!user.canAuthenticate()) {
			throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED,
					"This account is not active. Please contact support.");
		}

		user.markActive(clock.instant());
		users.save(user);

		return new AuthResult(user, tokenService.issueNewSession(user), newAccount);
	}

	/** Exchanges a refresh token for a new pair. */
	@Transactional
	public AuthResult refresh(String refreshToken) {
		Long userId = tokenService.findUserIdForRefreshToken(refreshToken);

		User user = users.findById(userId)
				.orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
						"Session expired. Please sign in again."));

		// Re-checked on every refresh: a user suspended after signing in must lose access at the
		// next rotation rather than keeping a working session for up to 30 days.
		if (!user.canAuthenticate()) {
			tokenService.revokeAllSessions(userId);
			throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED,
					"This account is not active. Please contact support.");
		}

		return new AuthResult(user, tokenService.rotate(refreshToken, user), false);
	}

	@Transactional
	public void logout(String refreshToken) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			tokenService.revoke(refreshToken);
		}
	}

	private static String normaliseOrReject(String rawPhone) {
		return PhoneNumbers.normalise(rawPhone)
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
						"Enter a valid 10-digit Indian mobile number."));
	}
}
