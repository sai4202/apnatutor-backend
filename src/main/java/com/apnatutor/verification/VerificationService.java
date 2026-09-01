package com.apnatutor.verification;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.storage.FileKind;
import com.apnatutor.billing.SignupBonusService;
import com.apnatutor.storage.FileStorage;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.verification.domain.Verification;
import com.apnatutor.verification.domain.VerificationLevel;
import com.apnatutor.verification.domain.VerificationStatus;
import com.apnatutor.verification.domain.VerificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submission and review of verification claims.
 *
 * <p>Verification is what the whole marketplace rests on. A parent is deciding whether to let a
 * stranger into their home with their child, and a badge is most of what they have to go on. A
 * badge granted carelessly is worse than no badge at all, because it converts our carelessness into
 * their misplaced confidence.
 *
 * <p>Consequently every approval is a deliberate admin act, recorded with who and when. Nothing
 * here approves automatically.
 */
@Service
public class VerificationService {

	private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

	private static final List<VerificationStatus> LIVE =
			List.of(VerificationStatus.PENDING, VerificationStatus.APPROVED);

	private final VerificationRepository verifications;
	private final UserRepository users;
	private final FileStorage fileStorage;
	private final SignupBonusService signupBonus;
	private final Clock clock;

	public VerificationService(
			VerificationRepository verifications,
			UserRepository users,
			FileStorage fileStorage,
			SignupBonusService signupBonus,
			Clock clock) {
		this.verifications = verifications;
		this.users = users;
		this.fileStorage = fileStorage;
		this.signupBonus = signupBonus;
		this.clock = clock;
	}

	/**
	 * Submits a document for review.
	 *
	 * <p>The document is stored as a private kind, so it is only ever readable through the
	 * admin-gated serving route.
	 */
	@Transactional
	public Verification submitDocument(Long userId, VerificationType type, byte[] document) {
		if (!type.requiresDocument()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					type.name() + " verification does not take a document.");
		}

		verifications.findFirstByUserIdAndTypeAndStatusIn(userId, type, LIVE)
				.ifPresent(existing -> {
					// The unique partial index in V7 would catch this anyway, but a constraint
					// violation surfaces as a 500. This gives the tutor a message they can act on.
					throw new ApiException(ErrorCode.CONFLICT,
							existing.getStatus() == VerificationStatus.APPROVED
									? "Your " + label(type) + " is already verified."
									: "Your " + label(type) + " is already awaiting review.");
				});

		FileKind kind = type == VerificationType.ID
				? FileKind.ID_DOCUMENT
				: FileKind.EDUCATION_DOCUMENT;
		FileStorage.StoredFile stored = fileStorage.store(document, kind);

		Verification verification =
				verifications.save(Verification.submit(userId, type, stored.storageKey()));

		log.info("Verification submitted: user={} type={}", userId, type);
		return verification;
	}

	@Transactional
	public Verification approve(Long verificationId, Long adminUserId) {
		Verification verification = verifications.findById(verificationId)
				.orElseThrow(() -> ApiException.notFound("Verification request"));

		try {
			verification.approve(adminUserId, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		}

		verifications.save(verification);

		// Deliberately logged at INFO with the reviewer's id: an approval is a trust decision
		// somebody may have to answer for later. A full audit table arrives at M5-08.
		log.info("Verification APPROVED: id={} user={} type={} by admin={}",
				verificationId, verification.getUserId(), verification.getType(), adminUserId);

		// SOURCE_OF_TRUTH.md §3.3. Runs in its own transaction, so a bonus that cannot be granted
		// — already given, settings row missing — never rolls back the admin's trust decision.
		if (levelFor(verification.getUserId()).isAtLeast(VerificationLevel.ID_VERIFIED)) {
			signupBonus.grantIfEarned(verification.getUserId());
		}

		return verification;
	}

	@Transactional
	public Verification reject(Long verificationId, Long adminUserId, String reason) {
		Verification verification = verifications.findById(verificationId)
				.orElseThrow(() -> ApiException.notFound("Verification request"));

		try {
			verification.reject(adminUserId, reason, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		verifications.save(verification);

		log.info("Verification REJECTED: id={} user={} type={} by admin={}",
				verificationId, verification.getUserId(), verification.getType(), adminUserId);

		return verification;
	}

	@Transactional(readOnly = true)
	public Page<Verification> pendingQueue(Pageable pageable) {
		return verifications.findByStatusOrderByCreatedAtAsc(VerificationStatus.PENDING, pageable);
	}

	@Transactional(readOnly = true)
	public List<Verification> forUser(Long userId) {
		return verifications.findByUserId(userId);
	}

	/**
	 * The set of approved claims for a user, plus phone read from the user record.
	 *
	 * <p>Phone is not a row in {@code verifications} — it lives on {@code users.phone_verified_at},
	 * written by the OTP flow. Keeping one source of truth for it is worth the small asymmetry here.
	 */
	@Transactional(readOnly = true)
	public Set<VerificationType> approvedTypes(Long userId) {
		Set<VerificationType> approved = EnumSet.noneOf(VerificationType.class);
		for (Verification verification
				: verifications.findByUserIdAndStatus(userId, VerificationStatus.APPROVED)) {
			approved.add(verification.getType());
		}
		return approved;
	}

	/** Derives the trust level. See {@link VerificationLevel} for why email is not a rung. */
	@Transactional(readOnly = true)
	public VerificationLevel levelFor(Long userId) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
		if (user.getPhoneVerifiedAt() == null) {
			return VerificationLevel.NONE;
		}

		Set<VerificationType> approved = approvedTypes(userId);
		if (approved.contains(VerificationType.ID)
				&& approved.contains(VerificationType.EDUCATION)) {
			return VerificationLevel.FULLY_VERIFIED;
		}
		if (approved.contains(VerificationType.ID)) {
			return VerificationLevel.ID_VERIFIED;
		}
		return VerificationLevel.PHONE_VERIFIED;
	}

	private static String label(VerificationType type) {
		return switch (type) {
			case ID -> "ID";
			case EDUCATION -> "education certificate";
			case EMAIL -> "email";
		};
	}
}
