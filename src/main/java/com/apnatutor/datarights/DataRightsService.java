package com.apnatutor.datarights;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apnatutor.abuse.AbuseReportRepository;
import com.apnatutor.audit.AuditContext;
import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.CreditTransactionRepository;
import com.apnatutor.billing.PaymentRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.datarights.dto.DataRightsDtos.DataExport;
import com.apnatutor.datarights.dto.DataRightsDtos.DeletionReceipt;
import com.apnatutor.lead.LeadUnlockRepository;
import com.apnatutor.notification.NotificationRepository;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.review.ReviewRepository;
import com.apnatutor.storage.FileStorage;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.verification.VerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Export and deletion under the DPDP Act — {@code M5-10}.
 *
 * <h2>Deletion anonymises; it never deletes the row</h2>
 *
 * <p>{@code M5-10.3}, and the reason is structural rather than legal. {@code credit_transactions}
 * is append-only by Invariant 1 and {@code audit_log} is append-only by {@code M5-08.4}. Both
 * reference {@code users.id}. Removing the row would either cascade rows out of a ledger that is
 * supposed to be immutable, or leave dangling references in one — so the row stays and every
 * personal field on it is emptied.
 *
 * <p>The phone is <em>replaced</em>, not cleared: the column is {@code NOT NULL}, uniquely indexed,
 * and CHECKed against E.164. {@code +99} is not an assigned country code, so a value built from it
 * cannot collide with a real number.
 *
 * <h2>What is destroyed outright</h2>
 *
 * <p>Identity and education documents. Those are Aadhaar and PAN scans; there is no argument for
 * keeping them past the account, and no ledger references them. The files go, and the rows that
 * pointed at them keep only the fact that a verification happened.
 */
@Service
public class DataRightsService {

	private static final Logger log = LoggerFactory.getLogger(DataRightsService.class);

	private final UserRepository users;
	private final StudentProfileRepository studentProfiles;
	private final TutorProfileRepository tutorProfiles;
	private final RequirementRepository requirements;
	private final LeadUnlockRepository unlocks;
	private final CreditTransactionRepository transactions;
	private final PaymentRepository payments;
	private final ReviewRepository reviews;
	private final AbuseReportRepository reports;
	private final NotificationRepository notifications;
	private final VerificationRepository verifications;
	private final CreditLedger ledger;
	private final FileStorage files;
	private final Clock clock;

	public DataRightsService(
			UserRepository users,
			StudentProfileRepository studentProfiles,
			TutorProfileRepository tutorProfiles,
			RequirementRepository requirements,
			LeadUnlockRepository unlocks,
			CreditTransactionRepository transactions,
			PaymentRepository payments,
			ReviewRepository reviews,
			AbuseReportRepository reports,
			NotificationRepository notifications,
			VerificationRepository verifications,
			CreditLedger ledger,
			FileStorage files,
			Clock clock) {
		this.users = users;
		this.studentProfiles = studentProfiles;
		this.tutorProfiles = tutorProfiles;
		this.requirements = requirements;
		this.unlocks = unlocks;
		this.transactions = transactions;
		this.payments = payments;
		this.reviews = reviews;
		this.reports = reports;
		this.notifications = notifications;
		this.verifications = verifications;
		this.ledger = ledger;
		this.files = files;
		this.clock = clock;
	}

	/**
	 * Everything held about one person — {@code M5-10.1}.
	 *
	 * <p>Includes the money records. A tutor asking what we hold about them is entitled to the
	 * ledger that explains their balance, and it is the section they are most likely to actually
	 * want.
	 */
	@Transactional(readOnly = true)
	public DataExport export(Long userId) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));
		boolean tutor = user.getRole() == UserRole.TUTOR;

		return new DataExport(
				clock.instant(),
				map(
						"id", user.getId(),
						"phone", user.getPhone(),
						"email", user.getEmail(),
						"role", user.getRole().name(),
						"status", user.getStatus().name(),
						"joinedAt", user.getCreatedAt(),
						"lastActiveAt", user.getLastActiveAt()),
				profileOf(user),
				requirements.findByStudentIdOrderByCreatedAtDesc(userId).stream()
						.map(this::describeRequirement)
						.toList(),
				unlocks.findByTutorIdOrderByUnlockedAtDesc(userId).stream()
						.map(unlock -> map(
								"requirementId", unlock.getRequirementId(),
								"creditsSpent", unlock.getCreditsSpent(),
								"status", unlock.getStatus().name(),
								"unlockedAt", unlock.getUnlockedAt()))
						.toList(),
				transactions.findByTutorIdOrderByCreatedAtDesc(userId).stream()
						.map(entry -> map(
								"amount", entry.getAmount(),
								"reason", entry.getReason().name(),
								"balanceAfter", entry.getBalanceAfter(),
								"expiresAt", entry.getExpiresAt(),
								"at", entry.getCreatedAt()))
						.toList(),
				payments.findByTutorIdOrderByCreatedAtDesc(userId).stream()
						.map(payment -> map(
								"credits", payment.getCredits(),
								"amountPaise", payment.getAmountPaise(),
								"status", payment.getStatus().name(),
								"at", payment.getCreatedAt()))
						.toList(),
				reviews.findByStudentIdOrderByCreatedAtDesc(userId).stream()
						.map(review -> map(
								"aboutTutorId", review.getTutorId(),
								"rating", review.getRating(),
								"title", review.getTitle(),
								"body", review.getBody(),
								"status", review.getStatus().name(),
								"at", review.getCreatedAt()))
						.toList(),
				tutor
						? reviews.findByTutorIdOrderByCreatedAtDesc(userId).stream()
								.map(review -> map(
										"rating", review.getRating(),
										"title", review.getTitle(),
										"body", review.getBody(),
										"status", review.getStatus().name(),
										"yourReply", review.getTutorReply(),
										"at", review.getCreatedAt()))
								.toList()
						: List.of(),
				reports.findByReporterIdOrderByCreatedAtDesc(userId).stream()
						.map(report -> map(
								"subjectType", report.getSubjectType().name(),
								"subjectId", report.getSubjectId(),
								"reason", report.getReason().name(),
								"status", report.getStatus().name(),
								"at", report.getCreatedAt()))
						.toList(),
				notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
						.map(notification -> map(
								"type", notification.getType().name(),
								"title", notification.getTitle(),
								"at", notification.getCreatedAt()))
						.toList(),
				List.of(
						"Reviews you wrote appear here in full. Publicly they show a masked name "
								+ "such as \"Priya S.\", never your full name.",
						"Contact details of other people are not included, even where you paid to "
								+ "see them: they are that person's data, not yours.",
						"Uploaded identity documents are not included as files. Their existence is "
								+ "listed; the scans themselves are only ever served to an "
								+ "administrator, and are destroyed if you delete your account."));
	}

	/**
	 * Erases an account — {@code M5-10.2}, {@code M5-10.3}.
	 *
	 * <p>Order matters. Documents are destroyed first, because they are the only part that cannot
	 * be undone by a later run and the only part with no reason to survive a failure. The account
	 * row is emptied last, so a failure part-way leaves an account that can be deleted again rather
	 * than one that is half gone and can no longer sign in to ask.
	 */
	@Transactional
	public DeletionReceipt deleteAccount(Long userId) {
		User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));

		if (user.isDeleted()) {
			throw new ApiException(ErrorCode.CONFLICT, "This account has already been deleted.");
		}
		if (user.getRole() == UserRole.ADMIN) {
			// Same reasoning as refusing to suspend one: an admin deleting themselves can remove
			// the last account that could have undone it.
			throw ApiException.forbidden(
					"Admin accounts cannot be deleted here. Ask another administrator.");
		}

		int documentsDestroyed = destroyDocuments(userId);
		int enquiriesClosed = withdrawLiveEnquiries(userId);
		int creditsForfeited = user.getRole() == UserRole.TUTOR ? ledger.balanceOf(userId) : 0;

		anonymiseProfiles(userId);
		user.anonymise(clock.instant());
		users.save(user);

		AuditContext.describe("ACCOUNT_DELETED", "USER", userId);
		AuditContext.after(AuditContext.fields(
				"documentsDestroyed", documentsDestroyed,
				"enquiriesClosed", enquiriesClosed,
				"creditsForfeited", creditsForfeited));

		log.info("Account DELETED (anonymised): user={} documents={} enquiries={} credits={}",
				userId, documentsDestroyed, enquiriesClosed, creditsForfeited);

		return new DeletionReceipt(
				user.getDeletedAt(),
				enquiriesClosed,
				documentsDestroyed,
				creditsForfeited,
				List.of(
						"Credit ledger entries and payment records are kept. They are financial "
								+ "records, they are append-only, and they no longer identify you.",
						"Reviews you wrote stay published. They are about a tutor, other people "
								+ "have relied on them, and they never carried your full name.",
						"Moderation records of actions taken on your account are kept, without "
								+ "your personal details.",
						"Your phone number and email are gone, and cannot be recovered."));
	}

	/**
	 * Destroys uploaded identity and education documents.
	 *
	 * <p>The only thing in this whole flow that is genuinely deleted rather than anonymised. These
	 * are Aadhaar and PAN scans; nothing references them, no ledger depends on them, and there is
	 * no argument for keeping a government ID belonging to someone who has left.
	 *
	 * <p>A failure to remove one file does not abort the deletion. A user who asked to be forgotten
	 * being told "no" because one object store call failed is the worse outcome, and the row is
	 * cleared either way so nothing points at it — the file is then orphaned, which is a cleanup
	 * problem rather than a privacy one.
	 */
	private int destroyDocuments(Long userId) {
		int destroyed = 0;

		for (var verification : verifications.findByUserId(userId)) {
			String key = verification.getDocumentUrl();
			if (key == null) {
				continue;
			}
			try {
				files.delete(key);
				destroyed++;
			} catch (RuntimeException e) {
				log.error("Could not destroy document {} for deleted user {} — the reference is "
						+ "cleared regardless, leaving the file orphaned", key, userId, e);
			}
			verification.forgetDocument();
			verifications.save(verification);
		}

		return destroyed;
	}

	/** Live enquiries carry the student's phone as their whole point. They cannot outlive it. */
	private int withdrawLiveEnquiries(Long userId) {
		List<Requirement> live = requirements.findByStudentIdAndStatusIn(
				userId, List.of(RequirementStatus.OPEN, RequirementStatus.CAPPED));

		for (Requirement requirement : live) {
			requirement.close();
			requirements.save(requirement);
		}

		return live.size();
	}

	private void anonymiseProfiles(Long userId) {
		studentProfiles.findByUserId(userId).ifPresent(profile -> {
			profile.anonymise();
			studentProfiles.save(profile);
		});

		tutorProfiles.findByUserId(userId).ifPresent(profile -> {
			profile.anonymise();
			tutorProfiles.save(profile);
		});
	}

	private Map<String, Object> profileOf(User user) {
		if (user.getRole() == UserRole.TUTOR) {
			return tutorProfiles.findByUserId(user.getId())
					.map(profile -> map(
							"displayName", profile.getDisplayName(),
							"headline", profile.getHeadline(),
							"bio", profile.getBio(),
							"experienceYears", profile.getExperienceYears(),
							"published", profile.isPublished()))
					.orElseGet(Map::of);
		}

		return studentProfiles.findByUserId(user.getId())
				.map(profile -> map("name", profile.getName()))
				.orElseGet(Map::of);
	}

	private Map<String, Object> describeRequirement(Requirement requirement) {
		return map(
				"id", requirement.getId(),
				"subjectId", requirement.getSubjectId(),
				"mode", requirement.getMode(),
				"budgetAmountPaise", requirement.getBudgetAmountPaise(),
				"description", requirement.getDescription(),
				"status", requirement.getStatus().name(),
				"postedAt", requirement.getCreatedAt());
	}

	/** Insertion-ordered and null-tolerant, so the export reads in a sensible order. */
	private static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			result.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
		}
		return result;
	}
}
