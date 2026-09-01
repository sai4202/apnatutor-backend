package com.apnatutor.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apnatutor.auth.RefreshCookie;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.LocationRepository;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tutor profile flows end to end against a real database.
 *
 * <p>Covers the two things that matter beyond CRUD working:
 *
 * <ul>
 *   <li><strong>Ownership</strong> — a tutor can only ever touch their own profile, and a student
 *       cannot reach these endpoints at all. This is what {@code M1-05.3} and {@code M1-05.4} were
 *       waiting for something to guard.
 *   <li><strong>No contact leakage</strong> — the public projection must not carry a phone number,
 *       an email, or a document URL. This is the single highest-consequence bug class in the
 *       product: contact details are what tutors pay for.
 * </ul>
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class TutorProfileIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private LocationRepository locations;

	private Long leafSubjectId;
	private Long localityId;

	@BeforeEach
	void setUp() {
		sms.clear();
		leafSubjectId = subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc()
				.getFirst().getId();
		localityId = locations.findByCityLevelTrueAndActiveTrueOrderByDisplayOrderAsc()
				.getFirst().getId();
	}

	// --- Profile building ---------------------------------------------------------------------

	@Test
	@DisplayName("a new tutor starts with an empty profile that cannot be published")
	void newProfileStartsEmpty() throws Exception {
		String token = signIn("9700000001", UserRole.TUTOR);

		mockMvc.perform(get("/api/v1/tutor/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileCompleteness").value(0))
				.andExpect(jsonPath("$.published").value(false))
				// The list of what is missing is the difference between a tutor finishing their
				// profile and abandoning it at "0% complete" with no idea why.
				.andExpect(jsonPath("$.missingForPublish").isNotEmpty());

		mockMvc.perform(post("/api/v1/tutor/profile/publish")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));
	}

	@Test
	@DisplayName("a fully built profile publishes and becomes publicly visible")
	void completeProfilePublishes() throws Exception {
		String token = signIn("9700000002", UserRole.TUTOR);
		buildCompleteProfile(token);

		String published = mockMvc.perform(post("/api/v1/tutor/profile/publish")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.published").value(true))
				.andReturn().getResponse().getContentAsString();

		Integer profileId = JsonPath.read(published, "$.id");

		// Public read needs no authentication at all.
		mockMvc.perform(get("/api/v1/public/tutors/" + profileId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Ananya Reddy"))
				.andExpect(jsonPath("$.subjects").isNotEmpty());
	}

	@Test
	@DisplayName("an unpublished profile is not publicly readable")
	void unpublishedProfileIsHidden() throws Exception {
		String token = signIn("9700000003", UserRole.TUTOR);
		String profile = mockMvc.perform(get("/api/v1/tutor/profile")
						.header("Authorization", "Bearer " + token))
				.andReturn().getResponse().getContentAsString();
		Integer profileId = JsonPath.read(profile, "$.id");

		mockMvc.perform(get("/api/v1/public/tutors/" + profileId))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("editing a published profile below the bar unpublishes it")
	void droppingBelowThresholdUnpublishes() throws Exception {
		String token = signIn("9700000004", UserRole.TUTOR);
		buildCompleteProfile(token);
		mockMvc.perform(post("/api/v1/tutor/profile/publish")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		// Clearing fees alone only costs 15 points — not enough to fall below the bar, which is
		// correct behaviour. Stripping the written content as well does cross it.
		mockMvc.perform(put("/api/v1/tutor/profile/fees")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"feeNegotiable\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.published").value(true));

		// Leaving a profile live once it is this empty would put a broken listing in front of
		// parents, so it comes down automatically.
		mockMvc.perform(put("/api/v1/tutor/profile/basics")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"experienceYears\":0,\"offersDemo\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.published").value(false));
	}

	@Test
	@DisplayName("a category cannot be claimed as a subject")
	void categoriesAreNotTeachable() throws Exception {
		String token = signIn("9700000005", UserRole.TUTOR);
		Long categoryId = subjects.findBySlugAndActiveTrue("school-tuition").orElseThrow().getId();

		// "School Tuition" is navigation. Allowing it would put the tutor in every search.
		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"subjects\":[{\"subjectId\":" + categoryId + "}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("an unknown subject id is rejected")
	void unknownSubjectRejected() throws Exception {
		String token = signIn("9700000006", UserRole.TUTOR);

		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"subjects\":[{\"subjectId\":999999}]}"))
				.andExpect(status().isBadRequest());
	}

	// --- Authorization ------------------------------------------------------------------------

	@Test
	@DisplayName("a student cannot reach tutor profile endpoints")
	void studentCannotAccessTutorEndpoints() throws Exception {
		String studentToken = signIn("9700000007", UserRole.STUDENT);

		mockMvc.perform(get("/api/v1/tutor/profile")
						.header("Authorization", "Bearer " + studentToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		mockMvc.perform(put("/api/v1/tutor/profile/basics")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"experienceYears\":5}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("tutor endpoints reject an unauthenticated caller")
	void unauthenticatedRejected() throws Exception {
		mockMvc.perform(get("/api/v1/tutor/profile"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("one tutor's edits never touch another tutor's profile")
	void tutorsAreIsolatedFromEachOther() throws Exception {
		String tokenA = signIn("9700000008", UserRole.TUTOR);
		String tokenB = signIn("9700000009", UserRole.TUTOR);

		mockMvc.perform(put("/api/v1/tutor/profile/basics")
						.header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"displayName\":\"Tutor A\",\"experienceYears\":5}"))
				.andExpect(status().isOk());

		// There is no endpoint that takes a profile id to edit, so B has no way to address A's
		// profile at all — the isolation is structural, not a check that could be forgotten.
		mockMvc.perform(get("/api/v1/tutor/profile").header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").doesNotExist());
	}

	// --- The bug class that matters most --------------------------------------------------------

	@Test
	@DisplayName("the public profile leaks no contact details of any kind")
	void publicProfileLeaksNoContactDetails() throws Exception {
		String phone = "9700000010";
		String token = signIn(phone, UserRole.TUTOR);
		buildCompleteProfile(token);

		String published = mockMvc.perform(post("/api/v1/tutor/profile/publish")
						.header("Authorization", "Bearer " + token))
				.andReturn().getResponse().getContentAsString();
		Integer profileId = JsonPath.read(published, "$.id");

		String body = mockMvc.perform(get("/api/v1/public/tutors/" + profileId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// Contact details ARE the product — they are what tutors spend credits to unlock. A leak
		// here does not degrade the business model, it removes it.
		assertThat(body).doesNotContain(phone);
		assertThat(body).doesNotContain("+91" + phone);
		assertThat(body).doesNotContain("phone");
		assertThat(body).doesNotContain("email");
		assertThat(body).doesNotContain("documentUrl");
		assertThat(body).doesNotContain("dateOfBirth");
	}

	// --- Helpers --------------------------------------------------------------------------------

	private void buildCompleteProfile(String token) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/basics")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"Ananya Reddy",
						 "headline":"Physics & Maths, Classes 9-12",
						 "bio":"I teach Physics and Mathematics to students in Classes 9 to 12, \
						focusing on building intuition before formulas so equations stop feeling arbitrary.",
						 "experienceYears":8,
						 "languages":["English","Telugu"],
						 "offersDemo":true}""")).andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/fees")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feeMinPaise":450000,"feeMaxPaise":600000,
						 "feeUnit":"PER_MONTH","feeNegotiable":true}""")).andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"teachingModes":["STUDENT_HOME","ONLINE"],"travelRadiusKm":8,
						 "locationIds":[%d]}""".formatted(localityId))).andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"subjects":[{"subjectId":%d,"feePaise":450000,"feeUnit":"PER_MONTH"}]}"""
						.formatted(leafSubjectId))).andExpect(status().isOk());
	}

	/** Requests a code, reads it from the captured SMS, verifies it, and returns the access token. */
	private String signIn(String phone, UserRole role) throws Exception {
		mockMvc.perform(post("/api/v1/auth/otp/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isOk());

		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		ResultActions verified = mockMvc.perform(post("/api/v1/auth/otp/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"%s\",\"code\":\"%s\",\"role\":\"%s\"}"
								.formatted(phone, code, role.name())))
				.andExpect(status().isOk());

		return JsonPath.read(verified.andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}
