package com.apnatutor.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.lead.LeadPricingService;
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
 * Admin-configurable pricing.
 *
 * <p>The property that matters most here is the one in {@link #repricingDoesNotAffectPostedEnquiries}:
 * a change applies to new enquiries only. A tutor charged more than the figure they were shown has
 * been misled, whatever the pricing table now says — and that is a trust failure no refund fixes.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class ConfigurablePricingTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private LeadPricingService pricing;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	private Long subjectId;

	@BeforeEach
	void setUp() {
		sms.clear();
		subjectId = subjects.findBySlugAndActiveTrue("physics").orElseThrow().getId();
	}

	@Test
	@DisplayName("the seeded ladder matches SOURCE_OF_TRUTH §3.1")
	void seededBandsMatchTheSpec() {
		assertThat(pricing.creditsFor(100_000L, "STUDENT_HOME")).isEqualTo(3);
		assertThat(pricing.creditsFor(350_000L, "STUDENT_HOME")).isEqualTo(5);
		assertThat(pricing.creditsFor(750_000L, "STUDENT_HOME")).isEqualTo(8);
		assertThat(pricing.creditsFor(2_000_000L, "STUDENT_HOME")).isEqualTo(12);
	}

	@Test
	@DisplayName("an admin can reprice a band without a deploy")
	void adminCanReprice() throws Exception {
		String adminToken = signInAsAdmin("9200000001");

		String bands = mockMvc.perform(get("/api/v1/admin/settings/pricing/bands")
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		Integer topBandId = JsonPath.read(bands, "$[3].id");

		mockMvc.perform(put("/api/v1/admin/settings/pricing/bands/" + topBandId)
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"credits\":20,\"label\":\"Rs 10,000 and above\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.credits").value(20));

		assertThat(pricing.creditsFor(2_000_000L, "STUDENT_HOME")).isEqualTo(20);
	}

	@Test
	@DisplayName("repricing does not change what an already-posted enquiry costs")
	void repricingDoesNotAffectPostedEnquiries() throws Exception {
		String studentToken = signIn("9200000002", UserRole.STUDENT);
		String adminToken = signInAsAdmin("9200000003");

		// Posted at the current price.
		String posted = mockMvc.perform(post("/api/v1/student/requirements")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"subjectId":%d,"mode":"STUDENT_HOME",
								 "budgetAmountPaise":350000,"budgetUnit":"PER_MONTH"}"""
								.formatted(subjectId)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		Integer requirementId = JsonPath.read(posted, "$.id");

		// An admin doubles the middle band.
		String bands = mockMvc.perform(get("/api/v1/admin/settings/pricing/bands")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn().getResponse().getContentAsString();
		Integer middleBandId = JsonPath.read(bands, "$[1].id");

		mockMvc.perform(put("/api/v1/admin/settings/pricing/bands/" + middleBandId)
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"credits\":50,\"label\":\"Repriced\"}")).andExpect(status().isOk());

		// The already-posted enquiry still costs what it did. A tutor who saw 5 credits must be
		// charged 5 — this is the whole reason the price is locked at creation.
		String tutorToken = signIn("9200000004", UserRole.TUTOR);
		Long tutorUserId = users.findByPhone("+919200000004").orElseThrow().getId();
		buildTutorProfile(tutorToken);

		mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.content[?(@.id == %d)].unlockCostCredits".formatted(requirementId))
						.value(org.hamcrest.Matchers.hasItem(5)));

		// A NEW enquiry at the same budget gets the new price.
		String newPosted = mockMvc.perform(post("/api/v1/student/requirements")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"subjectId":%d,"mode":"STUDENT_HOME",
								 "budgetAmountPaise":350000,"budgetUnit":"PER_MONTH"}"""
								.formatted(subjectId)))
				.andReturn().getResponse().getContentAsString();
		Integer newRequirementId = JsonPath.read(newPosted, "$.id");

		mockMvc.perform(get("/api/v1/tutor/leads")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$.content[?(@.id == %d)].unlockCostCredits".formatted(newRequirementId))
						.value(org.hamcrest.Matchers.hasItem(50)));
	}

	@Test
	@DisplayName("a setting outside its own bounds is refused")
	void boundsAreEnforced() throws Exception {
		String adminToken = signInAsAdmin("9200000005");

		// An unlock cap of 500 is not a configuration choice, it is an outage for every parent.
		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"500\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"0\"}"))
				.andExpect(status().isBadRequest());

		// Within range is fine.
		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"7\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.value").value("7"));

		// Put it back so later tests are unaffected.
		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"value\":\"5\"}")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("a whole-number setting rejects a decimal")
	void integerSettingsRejectDecimals() throws Exception {
		String adminToken = signInAsAdmin("9200000006");

		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"4.5\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("the lowest pricing band cannot be removed")
	void lowestBandIsProtected() throws Exception {
		String adminToken = signInAsAdmin("9200000007");

		String bands = mockMvc.perform(get("/api/v1/admin/settings/pricing/bands")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn().getResponse().getContentAsString();
		Integer lowestBandId = JsonPath.read(bands, "$[0].id");

		// Removing it would leave the smallest budgets with no price at all.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/v1/admin/settings/pricing/bands/" + lowestBandId)
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("a band cannot be priced at zero credits")
	void bandsCannotBeFree() throws Exception {
		String adminToken = signInAsAdmin("9200000008");

		String bands = mockMvc.perform(get("/api/v1/admin/settings/pricing/bands")
						.header("Authorization", "Bearer " + adminToken))
				.andReturn().getResponse().getContentAsString();
		Integer bandId = JsonPath.read(bands, "$[0].id");

		// A free lead is the business model given away by a configuration change.
		mockMvc.perform(put("/api/v1/admin/settings/pricing/bands/" + bandId)
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"credits\":0,\"label\":\"Free\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("only admins can change pricing")
	void pricingIsAdminOnly() throws Exception {
		String tutorToken = signIn("9200000009", UserRole.TUTOR);

		mockMvc.perform(get("/api/v1/admin/settings/pricing/bands")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(put("/api/v1/admin/settings/lead.unlock_cap")
						.header("Authorization", "Bearer " + tutorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"value\":\"1\"}"))
				.andExpect(status().isForbidden());
	}

	// --- Helpers ------------------------------------------------------------------------------

	private void buildTutorProfile(String token) throws Exception {
		mockMvc.perform(put("/api/v1/tutor/profile/subjects")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"subjects\":[{\"subjectId\":%d}]}".formatted(subjectId)))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/tutor/profile/teaching")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teachingModes\":[\"ONLINE\",\"STUDENT_HOME\"],\"travelRadiusKm\":10}"))
				.andExpect(status().isOk());
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

	private String signInAsAdmin(String phone) throws Exception {
		users.save(User.registerVerified("+91" + phone, UserRole.ADMIN, clock.instant()));

		mockMvc.perform(post("/api/v1/auth/otp/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		String body = mockMvc.perform(post("/api/v1/auth/otp/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return JsonPath.read(body, "$.accessToken");
	}
}
