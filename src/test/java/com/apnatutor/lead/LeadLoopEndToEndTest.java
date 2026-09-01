package com.apnatutor.lead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The money path, end to end over HTTP — SOURCE_OF_TRUTH.md verification §5.
 *
 * <p>A parent posts a requirement, a tutor sees it masked in their feed, spends credits to unlock
 * it, and gets the phone number. Everything else in the product exists to make this transaction
 * happen.
 *
 * <p>Distinct from {@link LeadUnlockConcurrencyTest}, which drives the service directly to test
 * locking. This one goes through the controllers, so it also covers authorization, the DTO
 * boundaries, and — most importantly — that the masked preview really is masked.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class LeadLoopEndToEndTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private LocationRepository locations;

	@Autowired
	private Clock clock;

	@Autowired
	private NotificationService notifications;

	private Long subjectId;
	private Long locationId;

	@BeforeEach
	void setUp() {
		sms.clear();
		subjectId = subjects.findBySlugAndActiveTrue("mathematics").orElseThrow().getId();
		locationId = locations.findBySlugAndActiveTrue("hyderabad").orElseThrow().getId();
	}

	@Test
	@DisplayName("the full loop: post, appear masked in the feed, unlock, reveal")
	void fullMoneyPath() throws Exception {
		String studentPhone = "9300000001";
		String studentToken = signIn(studentPhone, UserRole.STUDENT);

		// 1. The parent posts, for free.
		String posted = mockMvc.perform(post("/api/v1/student/requirements")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"subjectId":%d,"locationId":%d,"mode":"STUDENT_HOME",
								 "budgetAmountPaise":300000,"budgetUnit":"PER_MONTH",
								 "description":"Class 10 maths, twice a week"}"""
								.formatted(subjectId, locationId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.responseCount").value(0))
				.andExpect(jsonPath("$.remainingSlots").value(5))
				.andReturn().getResponse().getContentAsString();

		Integer requirementId = JsonPath.read(posted, "$.id");

		// 2. A tutor who teaches the subject in that city sees it — masked.
		String tutorToken = signIn("9300000002", UserRole.TUTOR);
		Long tutorUserId = users.findByPhone("+919300000002").orElseThrow().getId();
		buildTutorProfile(tutorToken);
		grantCredits(tutorUserId, 20);

		// Asserted on OUR requirement rather than the feed's total. Tests in this class share a
		// schema, so other requirements legitimately appear here — a total-count assertion would
		// be testing the order tests happen to run in.
		String feed = mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.id == %d)].unlockCostCredits".formatted(requirementId))
						.value(org.hamcrest.Matchers.hasItem(5)))
				.andExpect(jsonPath("$.content[?(@.id == %d)].remainingSlots".formatted(requirementId))
						.value(org.hamcrest.Matchers.hasItem(5)))
				.andReturn().getResponse().getContentAsString();

		// THE MASK. This is what makes an unlock worth paying for: if the tutor could read the
		// number here, they would never spend a credit.
		assertThat(feed).doesNotContain(studentPhone);
		assertThat(feed).doesNotContain("+91" + studentPhone);
		assertThat(feed).doesNotContain("studentPhone");
		assertThat(feed).doesNotContain("studentName");

		// 3. Unlock. Charged the price locked at posting, not a recomputed one.
		String unlocked = mockMvc.perform(post("/api/v1/tutor/leads/" + requirementId + "/unlock")
						.header("Authorization", "Bearer " + tutorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"introMessage\":\"I teach Class 10 maths in Gachibowli.\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.creditsSpent").value(5))
				.andReturn().getResponse().getContentAsString();

		// 4. And now the number is there. This is what the credits bought.
		assertThat(unlocked).contains("+91" + studentPhone);

		// 5. Balance debited exactly once.
		mockMvc.perform(get("/api/v1/tutor/leads/wallet")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.balance").value(15));

		// 6. The parent sees the response, with the tutor's number so they can call back.
		mockMvc.perform(get("/api/v1/student/requirements/" + requirementId)
						.header("Authorization", "Bearer " + studentToken))
				.andExpect(jsonPath("$.responseCount").value(1))
				.andExpect(jsonPath("$.remainingSlots").value(4))
				.andExpect(jsonPath("$.respondingTutors[0].phone").value("+919300000002"))
				.andExpect(jsonPath("$.respondingTutors[0].introMessage")
						.value("I teach Class 10 maths in Gachibowli."));

		// 7. The lead leaves that tutor's feed — they already own it, and showing a tutor a lead
		// they have paid for is how they end up trying to buy it twice.
		mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(requirementId))
						.value(org.hamcrest.Matchers.empty()));
	}

	@Test
	@DisplayName("unlocking twice charges once and returns the lead both times")
	void unlockingTwiceChargesOnce() throws Exception {
		String studentToken = signIn("9300000003", UserRole.STUDENT);
		Integer requirementId = postRequirement(studentToken);

		String tutorToken = signIn("9300000004", UserRole.TUTOR);
		Long tutorUserId = users.findByPhone("+919300000004").orElseThrow().getId();
		buildTutorProfile(tutorToken);
		grantCredits(tutorUserId, 20);

		mockMvc.perform(post("/api/v1/tutor/leads/" + requirementId + "/unlock")
				.header("Authorization", "Bearer " + tutorToken)).andExpect(status().isOk());

		// The retry a flaky mobile connection produces. It must hand back the lead that was
		// already paid for — an error here would leave the tutor charged and holding nothing.
		mockMvc.perform(post("/api/v1/tutor/leads/" + requirementId + "/unlock")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.studentPhone").value("+919300000003"));

		// The point of the test: replaying it is free.
		mockMvc.perform(get("/api/v1/tutor/leads/wallet")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.balance").value(15));

		// And exactly one debit was ever written, not two that cancel out.
		mockMvc.perform(get("/api/v1/tutor/leads/wallet")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.history[?(@.reason == 'UNLOCK')]")
						.value(org.hamcrest.Matchers.hasSize(1)));
	}

	@Test
	@DisplayName("a tutor without credits is refused and charged nothing")
	void insufficientCredits() throws Exception {
		String studentToken = signIn("9300000005", UserRole.STUDENT);
		Integer requirementId = postRequirement(studentToken);

		String tutorToken = signIn("9300000006", UserRole.TUTOR);
		buildTutorProfile(tutorToken);
		// No credits granted.

		mockMvc.perform(post("/api/v1/tutor/leads/" + requirementId + "/unlock")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isPaymentRequired())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDITS"));

		mockMvc.perform(get("/api/v1/tutor/leads/wallet")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.balance").value(0))
				// A refusal is not a financial event, so the history stays empty.
				.andExpect(jsonPath("$.history").isEmpty());
	}

	@Test
	@DisplayName("closing a requirement stops further responses")
	void closedRequirementRefusesUnlocks() throws Exception {
		String studentToken = signIn("9300000007", UserRole.STUDENT);
		Integer requirementId = postRequirement(studentToken);

		mockMvc.perform(post("/api/v1/student/requirements/" + requirementId + "/hired")
						.header("Authorization", "Bearer " + studentToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("HIRED"));

		String tutorToken = signIn("9300000008", UserRole.TUTOR);
		Long tutorUserId = users.findByPhone("+919300000008").orElseThrow().getId();
		buildTutorProfile(tutorToken);
		grantCredits(tutorUserId, 20);

		mockMvc.perform(post("/api/v1/tutor/leads/" + requirementId + "/unlock")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUIREMENT_NOT_OPEN"));
	}

    @Test
	@DisplayName("a student cannot read another student's requirement")
	void studentsCannotReadEachOther() throws Exception {
		String ownerToken = signIn("9300000009", UserRole.STUDENT);
		Integer requirementId = postRequirement(ownerToken);

		String otherToken = signIn("9300000010", UserRole.STUDENT);

		// 404, not 403 — a 403 would confirm the requirement exists, which is more than an
		// attacker enumerating ids should learn.
		mockMvc.perform(get("/api/v1/student/requirements/" + requirementId)
						.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a tutor cannot post a requirement, a student cannot browse leads")
	void rolesAreSeparated() throws Exception {
		String tutorToken = signIn("9300000011", UserRole.TUTOR);
		String studentToken = signIn("9300000012", UserRole.STUDENT);

		mockMvc.perform(post("/api/v1/student/requirements")
						.header("Authorization", "Bearer " + tutorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"subjectId\":%d,\"mode\":\"ONLINE\"}".formatted(subjectId)))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + studentToken))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an unpublished tutor is shown no leads at all")
	void unpublishedTutorSeesNothing() throws Exception {
		String studentToken = signIn("9300000013", UserRole.STUDENT);
		postRequirement(studentToken);

		// Matches the enquiry on subject and location, but has never published. A parent would
		// have no profile to check them against, so they must not be able to buy the phone number.
		String tutorToken = signIn("9300000014", UserRole.TUTOR);
		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
				.header("Authorization", "Bearer " + tutorToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjects\":[{\"subjectId\":%d}]}".formatted(subjectId)))
				.andExpect(status().isOk());
		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + tutorToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"teachingModes":["STUDENT_HOME"],"travelRadiusKm":10,"locationIds":[%d]}"""
						.formatted(locationId))).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	@DisplayName("posting an enquiry notifies matching tutors, and only matching ones")
	void postingNotifiesMatchingTutors() throws Exception {
		// Teaches the subject in the right city.
		String matching = signIn("9300000015", UserRole.TUTOR);
		buildTutorProfile(matching);
		Long matchingId = users.findByPhone("+919300000015").map(User::getId).orElseThrow();

		// Published and complete, but teaches a different subject.
		String other = signIn("9300000016", UserRole.TUTOR);
		buildTutorProfileForSubject(
				other, subjects.findBySlugAndActiveTrue("physics").orElseThrow().getId());
		Long otherId = users.findByPhone("+919300000016").map(User::getId).orElseThrow();

		String studentToken = signIn("9300000017", UserRole.STUDENT);
		postRequirement(studentToken);

		assertThat(notifications.forUser(matchingId))
				.as("the maths tutor should be told a maths enquiry was posted")
				.anyMatch(n -> n.getType() == NotificationType.NEW_MATCHING_LEAD);

		assertThat(notifications.forUser(otherId))
				.as("the physics tutor teaches something else and must not be pinged")
				.noneMatch(n -> n.getType() == NotificationType.NEW_MATCHING_LEAD);
	}

	// --- Helpers ------------------------------------------------------------------------------

	private Integer postRequirement(String studentToken) throws Exception {
		String posted = mockMvc.perform(post("/api/v1/student/requirements")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"subjectId":%d,"locationId":%d,"mode":"STUDENT_HOME",
								 "budgetAmountPaise":300000,"budgetUnit":"PER_MONTH"}"""
								.formatted(subjectId, locationId)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(posted, "$.id");
	}

	/**
	 * Builds a profile complete enough to publish, and publishes it.
	 *
	 * <p>The publish step is not incidental: only published tutors see leads, because an
	 * unpublished one unlocking a lead would put a stranger on a parent's phone with no profile
	 * for the parent to check them against.
	 */
	private void buildTutorProfile(String token) throws Exception {
		buildTutorProfileForSubject(token, subjectId);
	}

	private void buildTutorProfileForSubject(String token, Long subject) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/basics")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"Test Tutor","headline":"Experienced tutor",
						 "bio":"I teach with a focus on building intuition before formulas so that \
						the equations stop feeling arbitrary to my students.",
						 "experienceYears":8,"offersDemo":true}"""))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/fees")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"feeMinPaise\":400000,\"feeUnit\":\"PER_MONTH\",\"feeNegotiable\":true}"))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjects\":[{\"subjectId\":%d}]}".formatted(subject)))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"teachingModes":["STUDENT_HOME"],"travelRadiusKm":10,"locationIds":[%d]}"""
						.formatted(locationId))).andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/tutor/profile/publish")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

	/** Granted directly rather than through the admin API, to keep the test focused. */
	private void grantCredits(Long tutorUserId, int credits) {
		ledger.grant(tutorUserId, credits,
				com.apnatutor.billing.domain.CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
	}

	@Autowired
	private com.apnatutor.billing.CreditLedger ledger;

	private String signIn(String phone, UserRole role) throws Exception {
		mockMvc.perform(post("/api/v1/auth/otp/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		String body = mockMvc.perform(post("/api/v1/auth/otp/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"%s\",\"code\":\"%s\",\"role\":\"%s\"}"
								.formatted(phone, code, role.name())))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return JsonPath.read(body, "$.accessToken");
	}
}
