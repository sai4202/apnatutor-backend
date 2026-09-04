package com.apnatutor.datarights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.CreditTransactionRepository;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.datarights.dto.DataRightsDtos.DataExport;
import com.apnatutor.datarights.dto.DataRightsDtos.DeletionReceipt;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.domain.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Export and deletion — {@code M5-10}.
 *
 * <p>{@code M5-10.3} is the requirement everything else here serves: deletion must not corrupt the
 * financial ledger. The ledger is append-only and references the account, so the row is emptied
 * rather than removed — and the test that would fail if somebody ever "simplified" this into a
 * {@code DELETE} is {@link #deletionKeepsTheLedgerIntact}.
 */
class DataRightsTest extends AbstractIntegrationTest {

	@Autowired
	private DataRightsService dataRights;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private CreditTransactionRepository transactions;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private StudentProfileRepository studentProfiles;

	@Autowired
	private TutorProfileRepository tutorProfiles;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("deleting a tutor leaves every ledger entry standing and still reconciling")
	void deletionKeepsTheLedgerIntact() {
		Long tutorId = fundedTutor(40);
		Requirement requirement = postRequirement();
		unlockService.unlock(requirement.getId(), tutorId, null);

		int entriesBefore = transactions.findByTutorIdOrderByCreatedAtDesc(tutorId).size();
		assertThat(entriesBefore).isGreaterThan(1);

		dataRights.deleteAccount(tutorId);

		assertThat(transactions.findByTutorIdOrderByCreatedAtDesc(tutorId))
				.as("THE POINT (M5-10.3): credit_transactions is append-only and references the "
						+ "account. Deleting the row would either cascade rows out of an immutable "
						+ "ledger or leave dangling references in one")
				.hasSize(entriesBefore);

		assertThat(ledger.reconcile(tutorId))
				.as("and the ledger still agrees with the cached balance")
				.isZero();

		assertThat(users.findById(tutorId))
				.as("the row survives its owner, which is the whole design")
				.isPresent();
	}

	@Test
	@DisplayName("deletion erases the personal fields and gives the phone a value that cannot collide")
	void deletionAnonymisesTheAccount() {
		Long studentId = student();
		String originalPhone = users.findById(studentId).orElseThrow().getPhone();

		dataRights.deleteAccount(studentId);

		User deleted = users.findById(studentId).orElseThrow();

		assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(deleted.getDeletedAt()).isNotNull();
		assertThat(deleted.getEmail()).isNull();
		assertThat(deleted.canAuthenticate()).isFalse();

		assertThat(deleted.getPhone())
				.as("the column is NOT NULL, uniquely indexed and CHECKed against E.164, so the "
						+ "anonymised value has to be a real-looking number that cannot be one. "
						+ "+99 is not an assigned country code")
				.isNotEqualTo(originalPhone)
				.startsWith("+99")
				.matches("^[+][1-9][0-9]{7,14}$");

		assertThat(users.findByPhone(originalPhone))
				.as("the old number is free, so the same person could sign up again")
				.isEmpty();
	}

	@Test
	@DisplayName("two deleted accounts do not collide on the anonymised number")
	void anonymisedNumbersAreUnique() {
		Long first = student();
		Long second = student();

		dataRights.deleteAccount(first);
		dataRights.deleteAccount(second);

		assertThat(users.findById(first).orElseThrow().getPhone())
				.as("built from the id, so uniqueness is by construction rather than by luck")
				.isNotEqualTo(users.findById(second).orElseThrow().getPhone());
	}

	@Test
	@DisplayName("deleting a student withdraws their live enquiries")
	void deletionWithdrawsLiveEnquiries() {
		Long studentId = student();
		Requirement live = postRequirementFor(studentId);

		DeletionReceipt receipt = dataRights.deleteAccount(studentId);

		assertThat(receipt.enquiriesClosed()).isEqualTo(1);
		assertThat(requirements.findById(live.getId()).orElseThrow().getStatus())
				.as("an enquiry exists to hand out a phone number that no longer exists")
				.isEqualTo(RequirementStatus.CLOSED);
	}

	@Test
	@DisplayName("deleting a tutor takes their profile out of search")
	void deletionUnpublishesTheProfile() {
		Long tutorId = fundedTutor(10);
		TutorProfile profile = TutorProfile.createFor(tutorId);
		profile.publish(clock.instant(), 0);
		Long profileId = tutorProfiles.save(profile).getId();

		dataRights.deleteAccount(tutorId);

		assertThat(tutorProfiles.findById(profileId).orElseThrow().isPublished()).isFalse();
		assertThat(tutorProfiles.findPublishedActiveById(profileId))
				.as("a departed tutor still in results would put parents in touch with nobody")
				.isEmpty();
		assertThat(tutorProfiles.findById(profileId).orElseThrow().getDisplayName()).isNull();
	}

	@Test
	@DisplayName("deletion is refused twice, and refused for an admin")
	void deletionIsGuarded() {
		Long studentId = student();
		dataRights.deleteAccount(studentId);

		assertThatThrownBy(() -> dataRights.deleteAccount(studentId))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.CONFLICT);

		Long adminId = users.save(User.registerVerified(
				uniquePhone("9146"), UserRole.ADMIN, clock.instant())).getId();

		assertThatThrownBy(() -> dataRights.deleteAccount(adminId))
				.as("an admin deleting themselves can remove the last account that could undo it")
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	@DisplayName("the export carries the money records, and says what it leaves out")
	void exportIncludesTheLedger() {
		Long tutorId = fundedTutor(25);
		unlockService.unlock(postRequirement().getId(), tutorId, null);

		DataExport export = dataRights.export(tutorId);

		assertThat(export.account()).containsKey("phone");
		assertThat(export.creditLedger())
				.as("a tutor asking what we hold about them is entitled to the ledger that "
						+ "explains their balance, and it is the part they actually want")
				.isNotEmpty();
		assertThat(export.leadsUnlocked()).isNotEmpty();
		assertThat(export.notes())
				.as("the notes are the honest part: they say what is deliberately absent")
				.isNotEmpty();
	}

	@Test
	@DisplayName("the export never carries another person's contact details")
	void exportDoesNotLeakOtherPeople() {
		Long studentId = student();
		String studentPhone = users.findById(studentId).orElseThrow().getPhone();

		Long tutorId = fundedTutor(25);
		Requirement requirement = postRequirementFor(studentId);
		unlockService.unlock(requirement.getId(), tutorId, null);

		DataExport export = dataRights.export(tutorId);

		assertThat(export.toString())
				.as("THE POINT: the tutor paid to see this number, and it is still not their "
						+ "data. An export is not a second way to buy a contact list")
				.doesNotContain(studentPhone);
	}

	// --- Fixtures -------------------------------------------------------------------------------

	private Requirement postRequirement() {
		return postRequirementFor(student());
	}

	private Requirement postRequirementFor(Long studentId) {
		return requirements.save(Requirement.post(
				studentId,
				subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId(),
				null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Data rights test requirement",
				5, 5,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long student() {
		User user = users.save(User.registerVerified(
				uniquePhone("9148"), UserRole.STUDENT, clock.instant()));
		studentProfiles.save(com.apnatutor.user.domain.StudentProfile.createFor(user.getId()));
		return user.getId();
	}

	private Long fundedTutor(int credits) {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9147"), UserRole.TUTOR, clock.instant()));
		ledger.grant(tutor.getId(), credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		return tutor.getId();
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
