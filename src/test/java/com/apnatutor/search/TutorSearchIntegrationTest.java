package com.apnatutor.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
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
 * Search, end to end against a real database.
 *
 * <p>The last test in this class is the one that matters most. Contact details are what tutors pay
 * credits to unlock; a public search response that leaks one does not degrade the business model,
 * it removes it.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class TutorSearchIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private LocationRepository locations;

	private Long mathsId;
	private Long physicsId;
	private Long hyderabadId;

	@BeforeEach
	void setUp() {
		sms.clear();
		mathsId = subjects.findBySlugAndActiveTrue("mathematics").orElseThrow().getId();
		physicsId = subjects.findBySlugAndActiveTrue("physics").orElseThrow().getId();
		hyderabadId = locations.findBySlugAndActiveTrue("hyderabad").orElseThrow().getId();
	}

	// --- Visibility ---------------------------------------------------------------------------

	@Test
	@DisplayName("an unpublished tutor never appears in search")
	void unpublishedTutorsAreInvisible() throws Exception {
		String token = signIn("9400000001", UserRole.TUTOR);
		buildProfile(token, "Draft Tutor", mathsId, 400000);
		// Deliberately not published.

		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Draft Tutor"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	@DisplayName("a published tutor is findable without signing in")
	void publishedTutorIsFindable() throws Exception {
		String token = signIn("9400000002", UserRole.TUTOR);
		buildProfile(token, "Ananya Reddy", mathsId, 450000);
		publish(token);

		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Ananya"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].displayName").value("Ananya Reddy"));
	}

	@Test
	@DisplayName("unpublishing removes a tutor from results again")
	void unpublishingHides() throws Exception {
		String token = signIn("9400000003", UserRole.TUTOR);
		buildProfile(token, "Temporary Tutor", mathsId, 400000);
		publish(token);

		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Temporary"))
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(post("/api/v1/tutor/profile/unpublish")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Temporary"))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	// --- Filters ------------------------------------------------------------------------------

	@Test
	@DisplayName("filtering by subject slug returns only tutors who teach it")
	void filtersBySubject() throws Exception {
		String mathsToken = signIn("9400000004", UserRole.TUTOR);
		buildProfile(mathsToken, "Maths Only", mathsId, 400000);
		publish(mathsToken);

		String physicsToken = signIn("9400000005", UserRole.TUTOR);
		buildProfile(physicsToken, "Physics Only", physicsId, 400000);
		publish(physicsToken);

		String body = mockMvc.perform(get("/api/v1/public/tutors").param("subject", "physics"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("Physics Only");
		assertThat(body).doesNotContain("Maths Only");
	}

	@Test
	@DisplayName("an online tutor matches a city filter they have no locality in")
	void onlineTutorsMatchEveryLocation() throws Exception {
		// A product decision worth asserting: an online tutor can teach a student anywhere, so
		// excluding them from a city search would hide the tutors most able to help.
		String token = signIn("9400000006", UserRole.TUTOR);
		buildProfileOnlineOnly(token, "Online Tutor", physicsId, 500000);
		publish(token);

		mockMvc.perform(get("/api/v1/public/tutors")
						.param("location", "bengaluru")
						.param("q", "Online Tutor"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("fee bounds compare against the starting fee")
	void filtersByFee() throws Exception {
		String cheap = signIn("9400000007", UserRole.TUTOR);
		buildProfile(cheap, "Budget Tutor", mathsId, 200000);
		publish(cheap);

		String premium = signIn("9400000008", UserRole.TUTOR);
		buildProfile(premium, "Premium Tutor", mathsId, 900000);
		publish(premium);

		String body = mockMvc.perform(get("/api/v1/public/tutors")
						.param("subject", "mathematics")
						.param("feeMaxPaise", "300000"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("Budget Tutor");
		assertThat(body).doesNotContain("Premium Tutor");
	}

	@Test
	@DisplayName("teaching mode filters correctly")
	void filtersByMode() throws Exception {
		String token = signIn("9400000009", UserRole.TUTOR);
		buildProfileOnlineOnly(token, "Remote Only", mathsId, 400000);
		publish(token);

		mockMvc.perform(get("/api/v1/public/tutors")
						.param("mode", "ONLINE").param("q", "Remote Only"))
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(get("/api/v1/public/tutors")
						.param("mode", "TUTOR_PLACE").param("q", "Remote Only"))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	@DisplayName("verified-only excludes tutors without an approved ID")
	void filtersByVerification() throws Exception {
		String token = signIn("9400000010", UserRole.TUTOR);
		buildProfile(token, "Unverified Tutor", mathsId, 400000);
		publish(token);

		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Unverified Tutor"))
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(get("/api/v1/public/tutors")
						.param("q", "Unverified Tutor").param("verifiedOnly", "true"))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	@DisplayName("free text matches a subject name, not just a tutor name")
	void freeTextMatchesSubjects() throws Exception {
		String token = signIn("9400000011", UserRole.TUTOR);
		buildProfile(token, "Zzz Unfindable Name", physicsId, 400000);
		publish(token);

		// Someone typing "physics" should find a physics tutor whatever they are called.
		mockMvc.perform(get("/api/v1/public/tutors").param("q", "Physics"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(
						org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
	}

	// --- Paging and sorting -------------------------------------------------------------------

	@Test
	@DisplayName("page size is capped so a caller cannot request the whole table")
	void pageSizeIsCapped() throws Exception {
		mockMvc.perform(get("/api/v1/public/tutors").param("size", "10000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(50));
	}

	@Test
	@DisplayName("an unknown sort value is rejected rather than reaching the SQL")
	void rejectsUnknownSort() throws Exception {
		// Sort comes from an enum, so an arbitrary string cannot reach the ORDER BY — which is the
		// usual way a dynamic query turns out to be injectable.
		mockMvc.perform(get("/api/v1/public/tutors").param("sort", "id; DROP TABLE users"))
				.andExpect(status().isBadRequest());
	}

	// --- The one that matters most ------------------------------------------------------------

	@Test
	@DisplayName("search results leak no contact detail of any kind")
	void searchLeaksNoContactDetails() throws Exception {
		String phone = "9400000012";
		String token = signIn(phone, UserRole.TUTOR);
		buildProfile(token, "Ananya Reddy", mathsId, 450000);
		publish(token);

		String body = mockMvc.perform(get("/api/v1/public/tutors").param("q", "Ananya"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// Contact details ARE the product — they are what a tutor spends credits to unlock. A leak
		// here does not degrade the business model, it removes it.
		assertThat(body).doesNotContain(phone);
		assertThat(body).doesNotContain("+91" + phone);
		assertThat(body).doesNotContain("phone");
		assertThat(body).doesNotContain("email");
		assertThat(body).doesNotContain("dateOfBirth");
		assertThat(body).doesNotContain("documentUrl");
		assertThat(body).doesNotContain("userId");
	}

	// --- Helpers ------------------------------------------------------------------------------

	private void buildProfile(String token, String name, Long subjectId, long feePaise)
			throws Exception {
		basics(token, name);
		fees(token, feePaise);
		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"teachingModes":["STUDENT_HOME"],"travelRadiusKm":8,"locationIds":[%d]}"""
						.formatted(hyderabadId))).andExpect(status().isOk());
		setSubject(token, subjectId);
	}

	private void buildProfileOnlineOnly(String token, String name, Long subjectId, long feePaise)
			throws Exception {
		basics(token, name);
		fees(token, feePaise);
		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teachingModes\":[\"ONLINE\"],\"travelRadiusKm\":0,\"locationIds\":[]}"))
				.andExpect(status().isOk());
		setSubject(token, subjectId);
	}

	private void basics(String token, String name) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/basics")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"%s","headline":"Experienced tutor",
						 "bio":"I teach with a focus on building intuition before formulas so that \
						the equations stop feeling arbitrary to my students.",
						 "experienceYears":8,"offersDemo":true}""".formatted(name)))
				.andExpect(status().isOk());
	}

	private void fees(String token, long feePaise) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/fees")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feeMinPaise":%d,"feeUnit":"PER_MONTH","feeNegotiable":true}"""
						.formatted(feePaise))).andExpect(status().isOk());
	}

	private void setSubject(String token, Long subjectId) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjects\":[{\"subjectId\":%d}]}".formatted(subjectId)))
				.andExpect(status().isOk());
	}

	private void publish(String token) throws Exception {
		mockMvc.perform(post("/api/v1/tutor/profile/publish")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

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
