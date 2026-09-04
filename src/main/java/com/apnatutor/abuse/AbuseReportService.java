package com.apnatutor.abuse;

import java.time.Clock;
import java.util.List;

import com.apnatutor.abuse.domain.AbuseReport;
import com.apnatutor.abuse.domain.ReportReason;
import com.apnatutor.abuse.domain.ReportStatus;
import com.apnatutor.abuse.domain.ReportSubjectType;
import com.apnatutor.audit.AuditContext;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.review.ReviewRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.TutorProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abuse reports — {@code M5-09}.
 *
 * <h2>A report is a signal, not an instruction</h2>
 *
 * <p>Nothing here suspends an account or takes an enquiry down. Upholding a report records that a
 * moderator agreed with it; acting on it is a separate, separately-audited decision through the
 * console. Wiring them together would make the report button a weapon — a handful of coordinated
 * reports could remove a competitor — and would remove the one step where a person looks.
 *
 * <h2>Reporting is for signed-in accounts only</h2>
 *
 * <p>An anonymous report button is a harassment tool with no cost to use. Requiring an account does
 * not make abuse impossible, but it makes it attributable, which is most of the deterrent.
 */
@Service
public class AbuseReportService {

	private static final Logger log = LoggerFactory.getLogger(AbuseReportService.class);

	private final AbuseReportRepository reports;
	private final UserRepository users;
	private final TutorProfileRepository tutorProfiles;
	private final ReviewRepository reviews;
	private final RequirementRepository requirements;
	private final NotificationService notifications;
	private final Clock clock;

	public AbuseReportService(
			AbuseReportRepository reports,
			UserRepository users,
			TutorProfileRepository tutorProfiles,
			ReviewRepository reviews,
			RequirementRepository requirements,
			NotificationService notifications,
			Clock clock) {
		this.reports = reports;
		this.users = users;
		this.tutorProfiles = tutorProfiles;
		this.reviews = reviews;
		this.requirements = requirements;
		this.notifications = notifications;
		this.clock = clock;
	}

	/**
	 * Files a report.
	 *
	 * @throws ApiException {@code NOT_FOUND} if the subject does not exist, {@code VALIDATION_FAILED}
	 *     for a self-report or a reason only the platform may use, {@code CONFLICT} if this reporter
	 *     already has an open report against the same subject
	 */
	@Transactional
	public AbuseReport report(
			Long reporterId,
			ReportSubjectType subjectType,
			Long subjectId,
			ReportReason reason,
			String details) {

		if (reason == ReportReason.HIGH_DISPUTE_RATE) {
			// Not a judgement a person is in a position to make — it is derived from data only the
			// platform holds. Allowing it would let anyone attach a credible-looking systemic
			// accusation to a tutor they had one bad call with.
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That reason is not available. Please choose another.");
		}

		Long subjectUserId = resolveSubjectOwner(subjectType, subjectId);
		if (reporterId.equals(subjectUserId)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"You cannot report your own account or content.");
		}

		try {
			AbuseReport report = reports.saveAndFlush(
					AbuseReport.raisedBy(reporterId, subjectType, subjectId, reason, details));

			log.info("Abuse report filed: id={} reporter={} subject={}#{} reason={}",
					report.getId(), reporterId, subjectType, subjectId, reason);

			AuditContext.describe("ABUSE_REPORT_FILED", subjectType.name(), subjectId);
			AuditContext.summarise(reason.name());

			return report;
		} catch (DataIntegrityViolationException e) {
			// The partial unique index fired. One person filing the same complaint repeatedly
			// would inflate the count a moderator reads, which is the number that decides
			// whether several people independently noticed the same thing.
			throw new ApiException(ErrorCode.CONFLICT,
					"You have already reported this, and it is still being looked at.");
		}
	}

	/**
	 * A report the platform raises about itself.
	 *
	 * <p>Idempotent by subject while one is still open: the dispute-rate signal fires on every new
	 * dispute, and a queue with forty identical entries for one tutor is a queue nobody reads.
	 */
	@Transactional
	public void raiseSystemReport(
			ReportSubjectType subjectType, Long subjectId, ReportReason reason, String details) {

		if (reports.existsBySubjectTypeAndSubjectIdAndStatus(
				subjectType, subjectId, ReportStatus.OPEN)) {
			return;
		}

		AbuseReport report = reports.save(
				AbuseReport.raisedBySystem(subjectType, subjectId, reason, details));

		log.warn("Platform raised an abuse report: id={} subject={}#{} reason={} — {}",
				report.getId(), subjectType, subjectId, reason, details);
	}

	@Transactional(readOnly = true)
	public Page<AbuseReport> queue(Pageable pageable) {
		return reports.findByStatusOrderByCreatedAtAsc(ReportStatus.OPEN, pageable);
	}

	@Transactional(readOnly = true)
	public Page<AbuseReport> all(Pageable pageable) {
		return reports.findAllByOrderByCreatedAtDesc(pageable);
	}

	@Transactional(readOnly = true)
	public List<AbuseReport> about(ReportSubjectType subjectType, Long subjectId) {
		return reports.findBySubjectTypeAndSubjectIdOrderByCreatedAtDesc(subjectType, subjectId);
	}

	@Transactional(readOnly = true)
	public long distinctReportersFor(ReportSubjectType subjectType, Long subjectId) {
		return reports.countDistinctReportersFor(subjectType, subjectId);
	}

	@Transactional(readOnly = true)
	public List<AbuseReport> filedBy(Long reporterId) {
		return reports.findByReporterIdOrderByCreatedAtDesc(reporterId);
	}

	/**
	 * Records a moderator's decision.
	 *
	 * <p>The reporter is told either way, and the note goes with it. A report that disappears into
	 * silence teaches the person who filed it that reporting does nothing, and they are usually the
	 * only witness to whatever happened.
	 *
	 * <p>Nothing else happens automatically — see the class javadoc.
	 */
	@Transactional
	public AbuseReport decide(
			Long reportId, ReportStatus outcome, Long adminUserId, String note) {

		AbuseReport report = reports.findById(reportId)
				.orElseThrow(() -> ApiException.notFound("Report"));

		try {
			report.decide(outcome, adminUserId, note, clock.instant());
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		reports.save(report);

		if (report.isFromAPerson()) {
			notifications.notify(
					report.getReporterId(),
					NotificationType.ABUSE_REPORT_REVIEWED,
					outcome == ReportStatus.UPHELD
							? "Thank you — we acted on your report"
							: "We looked into your report",
					note == null || note.isBlank()
							? "A moderator has reviewed what you reported."
							: note,
					"ABUSE_REPORT",
					report.getId());
		}

		AuditContext.describe(
				outcome == ReportStatus.UPHELD ? "ABUSE_REPORT_UPHELD" : "ABUSE_REPORT_DISMISSED",
				"ABUSE_REPORT",
				reportId);
		AuditContext.summarise(note);
		AuditContext.before(AuditContext.fields("status", ReportStatus.OPEN.name()));
		AuditContext.after(AuditContext.fields(
				"status", outcome.name(),
				"subjectType", report.getSubjectType().name(),
				"subjectId", report.getSubjectId()));

		log.info("Abuse report {}: id={} subject={}#{} by admin={}",
				outcome, reportId, report.getSubjectType(), report.getSubjectId(), adminUserId);

		return report;
	}

	/**
	 * Checks the subject exists, and returns the account behind it.
	 *
	 * <p>The account matters because it is what a self-report is checked against: reporting your own
	 * review, or your own enquiry, is the same thing as reporting yourself, and both are noise.
	 * A tutor is addressed by {@code tutor_profiles.id} here for the same reason reviews are
	 * (ADR #13) — it is the only tutor identifier the frontend has.
	 */
	private Long resolveSubjectOwner(ReportSubjectType subjectType, Long subjectId) {
		return switch (subjectType) {
			case TUTOR -> tutorProfiles.findById(subjectId)
					.map(TutorProfile::getUserId)
					.orElseThrow(() -> ApiException.notFound("Tutor"));
			case STUDENT -> users.findById(subjectId)
					.map(user -> user.getId())
					.orElseThrow(() -> ApiException.notFound("Student"));
			case REVIEW -> reviews.findById(subjectId)
					.map(review -> review.getStudentId())
					.orElseThrow(() -> ApiException.notFound("Review"));
			case REQUIREMENT -> requirements.findById(subjectId)
					.map(requirement -> requirement.getStudentId())
					.orElseThrow(() -> ApiException.notFound("Requirement"));
		};
	}
}
