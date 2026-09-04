package com.apnatutor.abuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.abuse.domain.AbuseReport;
import com.apnatutor.abuse.domain.ReportReason;
import com.apnatutor.abuse.domain.ReportSource;
import com.apnatutor.abuse.domain.ReportStatus;
import com.apnatutor.abuse.domain.ReportSubjectType;
import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.RefundService;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.billing.domain.RefundReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Abuse reporting — {@code M5-09}.
 *
 * <p>The property that matters most is what a report does <strong>not</strong> do: upholding one
 * changes nothing about the account it names. Wiring the two together would make the report button
 * a way to remove a competitor with a handful of coordinated clicks.
 */
class AbuseReportFlowTest extends AbstractIntegrationTest {

	@Autowired
	private AbuseReportService reports;

	@Autowired
	private RefundService refunds;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private TutorProfileRepository tutorProfiles;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("upholding a report changes nothing about the account it names")
	void upholdingDoesNotActOnTheSubject() {
		Long tutorUserId = tutorUser();
		Long profileId = profileFor(tutorUserId);

		AbuseReport report = reports.report(
				student(), ReportSubjectType.TUTOR, profileId, ReportReason.MISLEADING_CLAIMS,
				"Claims a degree they do not have");

		reports.decide(report.getId(), ReportStatus.UPHELD, admin(), "Confirmed");

		User tutor = users.findById(tutorUserId).orElseThrow();
		assertThat(tutor.canAuthenticate())
				.as("THE POINT: a report is a signal, not an instruction. If upholding suspended "
						+ "the account, a handful of coordinated reports would remove a competitor")
				.isTrue();
		assertThat(tutorProfiles.findById(profileId).orElseThrow().isPublished()).isTrue();
	}

	@Test
	@DisplayName("one person cannot file the same complaint twice while it is open")
	void oneOpenReportPerReporterPerSubject() {
		Long reporterId = student();
		Long profileId = profileFor(tutorUser());

		reports.report(reporterId, ReportSubjectType.TUTOR, profileId, ReportReason.SPAM, null);

		assertThatThrownBy(() -> reports.report(
						reporterId, ReportSubjectType.TUTOR, profileId, ReportReason.SPAM, null))
				.as("the count of distinct reporters is what a moderator reads, so one person "
						+ "filing fifty times must not look like fifty people")
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);
	}

	@Test
	@DisplayName("the same person may report again once the first was decided")
	void aDecidedReportFreesTheSlot() {
		Long reporterId = student();
		Long profileId = profileFor(tutorUser());

		AbuseReport first = reports.report(
				reporterId, ReportSubjectType.TUTOR, profileId, ReportReason.SPAM, null);
		reports.decide(first.getId(), ReportStatus.DISMISSED, admin(), "Nothing to act on");

		assertThat(reports.report(
						reporterId, ReportSubjectType.TUTOR, profileId,
						ReportReason.HARASSMENT, "It happened again"))
				.as("a second incident is real information, not a duplicate")
				.isNotNull();
	}

	@Test
	@DisplayName("you cannot report yourself, or use a reason only the platform may use")
	void reportsAreGuarded() {
		Long tutorUserId = tutorUser();
		Long ownProfile = profileFor(tutorUserId);
		Long reporterId = student();
		Long someProfile = profileFor(tutorUser());

		assertThatThrownBy(() -> reports.report(
						tutorUserId, ReportSubjectType.TUTOR, ownProfile, ReportReason.SPAM, null))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);

		assertThatThrownBy(() -> reports.report(
						reporterId, ReportSubjectType.TUTOR, someProfile,
						ReportReason.HIGH_DISPUTE_RATE, null))
				.as("HIGH_DISPUTE_RATE is derived from data only the platform holds; letting "
						+ "anyone claim it would attach a systemic-looking accusation to a tutor "
						+ "somebody had one bad call with")
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_FAILED);
	}

	@Test
	@DisplayName("reporting something that does not exist is a 404, not a silent no-op")
	void unknownSubjectsAreRejected() {
		assertThatThrownBy(() -> reports.report(
						student(), ReportSubjectType.REQUIREMENT, 999_999L,
						ReportReason.FAKE_ENQUIRY, null))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	@DisplayName("a high dispute rate raises the platform's own report, once")
	void theDisputeSignalRaisesASystemReport() {
		Long tutorUserId = fundedTutor(200);
		Long profileId = profileFor(tutorUserId);

		// Five disputes out of five unlocks: past both the count floor and the rate threshold.
		for (int i = 0; i < 5; i++) {
			Requirement requirement = postRequirement();
			LeadUnlock unlock = unlockService.unlock(requirement.getId(), tutorUserId, null);
			refunds.raise(unlock.getId(), tutorUserId, RefundReason.UNREACHABLE, "No answer");
		}

		List<AbuseReport> raised = reports.about(ReportSubjectType.TUTOR, profileId);

		assertThat(raised)
				.as("THE POINT: this signal spent two milestones as a log line nobody greps. "
						+ "It is a queue entry now")
				.hasSize(1);
		assertThat(raised.getFirst().getSource()).isEqualTo(ReportSource.SYSTEM);
		assertThat(raised.getFirst().getReason()).isEqualTo(ReportReason.HIGH_DISPUTE_RATE);
		assertThat(raised.getFirst().getReporterId())
				.as("nobody filed it, so there is nobody to notify when it is decided")
				.isNull();

		assertThat(reports.distinctReportersFor(ReportSubjectType.TUTOR, profileId))
				.as("'four people reported this tutor' and 'four reports, three of them ours' "
						+ "are different facts, so system reports are not counted as reporters")
				.isZero();
	}

	@Test
	@DisplayName("a report cannot be decided twice")
	void decisionsAreFinal() {
		AbuseReport report = reports.report(
				student(), ReportSubjectType.TUTOR, profileFor(tutorUser()),
				ReportReason.SPAM, null);

		Long adminId = admin();
		reports.decide(report.getId(), ReportStatus.UPHELD, adminId, "Confirmed");

		assertThatThrownBy(() ->
						reports.decide(report.getId(), ReportStatus.DISMISSED, adminId, "Actually no"))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);
	}

	@Test
	@DisplayName("the queue holds open reports and drops them once decided")
	void theQueueIsWorkToDo() {
		AbuseReport report = reports.report(
				student(), ReportSubjectType.TUTOR, profileFor(tutorUser()),
				ReportReason.HARASSMENT, "Abusive messages");

		assertThat(openQueueIds()).contains(report.getId());

		reports.decide(report.getId(), ReportStatus.UPHELD, admin(), "Acted on separately");

		assertThat(openQueueIds()).doesNotContain(report.getId());
	}

	// --- Fixtures -------------------------------------------------------------------------------

	private List<Long> openQueueIds() {
		return reports.queue(PageRequest.of(0, 100)).stream().map(AbuseReport::getId).toList();
	}

	private Long profileFor(Long tutorUserId) {
		TutorProfile profile = TutorProfile.createFor(tutorUserId);
		profile.publish(clock.instant(), 0);
		return tutorProfiles.save(profile).getId();
	}

	private Requirement postRequirement() {
		return requirements.save(Requirement.post(
				student(),
				subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId(),
				null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Abuse report test requirement",
				5, 5,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long student() {
		return users.save(User.registerVerified(
				uniquePhone("9158"), UserRole.STUDENT, clock.instant())).getId();
	}

	private Long tutorUser() {
		return users.save(User.registerVerified(
				uniquePhone("9157"), UserRole.TUTOR, clock.instant())).getId();
	}

	private Long fundedTutor(int credits) {
		Long tutorId = tutorUser();
		ledger.grant(tutorId, credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		return tutorId;
	}

	private Long admin() {
		return users.save(User.registerVerified(
				uniquePhone("9156"), UserRole.ADMIN, clock.instant())).getId();
	}

	/** Keeps phone numbers unique without a shared sequence between tests. */
	private static final List<String> ISSUED = new ArrayList<>();

	private static synchronized String uniquePhone(String prefix) {
		String phone;
		do {
			phone = "+91" + prefix + (100_000 + (int) (Math.random() * 899_999));
		} while (ISSUED.contains(phone));
		ISSUED.add(phone);
		return phone;
	}
}
