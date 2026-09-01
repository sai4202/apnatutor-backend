package com.apnatutor.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The verification ladder end to end.
 *
 * <p>Two properties carry most of the weight here: an ID document must never be reachable without
 * an admin session, and a badge must never appear without an admin having actually approved it. A
 * badge granted by accident converts our carelessness into a parent's misplaced confidence.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class VerificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private UserRepository users;

	@Autowired
	private Clock clock;

	@BeforeEach
	void resetSms() {
		sms.clear();
	}

	/** A minimal valid PDF — signature plus padding, which is all the detector inspects. */
	private static MockMultipartFile pdf() {
		byte[] bytes = new byte[64];
		byte[] signature = { 0x25, 0x50, 0x44, 0x46, 0x2D };
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		return new MockMultipartFile("file", "aadhaar.pdf", "application/pdf", bytes);
	}

	// --- Submission ---------------------------------------------------------------------------

	@Test
	@DisplayName("a tutor submits an ID and it lands in the queue as pending")
	void submitCreatesPendingRequest() throws Exception {
		String token = signIn("9600000001", UserRole.TUTOR);

		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("ID"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				// The tutor's own view must not echo the document key back.
				.andExpect(jsonPath("$.documentUrl").doesNotExist());
	}

	@Test
	@DisplayName("a second submission of the same type is refused while one is live")
	void refusesDuplicateSubmission() throws Exception {
		String token = signIn("9600000002", UserRole.TUTOR);

		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
				.file(pdf()).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		// Without this a tutor could flood the review queue with the same request.
		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	@Test
	@DisplayName("a disguised document is rejected before anything is stored")
	void rejectsDisguisedDocument() throws Exception {
		String token = signIn("9600000003", UserRole.TUTOR);
		MockMultipartFile html = new MockMultipartFile(
				"file", "aadhaar.pdf", "application/pdf",
				"<html><script>alert(1)</script></html>".getBytes());

		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(html).header("Authorization", "Bearer " + token))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// --- Review -------------------------------------------------------------------------------

	@Test
	@DisplayName("an approved ID raises the tutor's level and adds the badge")
	void approvalGrantsBadge() throws Exception {
		String tutorToken = signIn("9600000004", UserRole.TUTOR);
		String adminToken = signInAsAdmin("9600000005");

		String submitted = mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andReturn().getResponse().getContentAsString();
		Integer id = JsonPath.read(submitted, "$.id");

		// Before approval the tutor is only phone-verified.
		mockMvc.perform(get("/api/v1/tutor/verification/level")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(content().string("\"PHONE_VERIFIED\""));

		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/approve")
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"));

		mockMvc.perform(get("/api/v1/tutor/verification/level")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(content().string("\"ID_VERIFIED\""));
	}

	@Test
	@DisplayName("a rejection must carry a reason the tutor can act on")
	void rejectionRequiresReason() throws Exception {
		String tutorToken = signIn("9600000006", UserRole.TUTOR);
		String adminToken = signInAsAdmin("9600000007");

		String submitted = mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andReturn().getResponse().getContentAsString();
		Integer id = JsonPath.read(submitted, "$.id");

		// An empty reason is refused: "rejected" with no explanation leaves the tutor unable to fix
		// anything, so they either give up or resubmit the same document.
		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/reject")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"\"}"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/reject")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"The scan is too blurry to read.\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));

		// And the tutor can see why.
		mockMvc.perform(get("/api/v1/tutor/verification")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(jsonPath("$[0].rejectionReason").value("The scan is too blurry to read."));
	}

	@Test
	@DisplayName("a decided request cannot be decided again")
	void reviewIsOneShot() throws Exception {
		String tutorToken = signIn("9600000008", UserRole.TUTOR);
		String adminToken = signInAsAdmin("9600000009");

		String submitted = mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andReturn().getResponse().getContentAsString();
		Integer id = JsonPath.read(submitted, "$.id");

		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/approve")
				.header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());

		// Re-deciding would overwrite who reviewed it and when, destroying the audit trail that
		// makes the decision defensible later.
		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/approve")
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("a resubmission is allowed after a rejection")
	void resubmissionAllowedAfterRejection() throws Exception {
		String tutorToken = signIn("9600000010", UserRole.TUTOR);
		String adminToken = signInAsAdmin("9600000011");

		String submitted = mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andReturn().getResponse().getContentAsString();
		Integer id = JsonPath.read(submitted, "$.id");

		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/reject")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Too blurry.\"}")).andExpect(status().isOk());

		// The rejected row stays for history; the partial unique index only covers live rows.
		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	// --- Authorization ------------------------------------------------------------------------

	@Test
	@DisplayName("a tutor cannot reach the admin review queue")
	void tutorCannotReview() throws Exception {
		String tutorToken = signIn("9600000012", UserRole.TUTOR);

		mockMvc.perform(get("/api/v1/admin/verifications/pending")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a tutor cannot approve their own verification")
	void tutorCannotSelfApprove() throws Exception {
		String tutorToken = signIn("9600000013", UserRole.TUTOR);

		String submitted = mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
						.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andReturn().getResponse().getContentAsString();
		Integer id = JsonPath.read(submitted, "$.id");

		// The whole trust model collapses if this succeeds.
		mockMvc.perform(post("/api/v1/admin/verifications/" + id + "/approve")
						.header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("an ID document is not readable through the public file route")
	void idDocumentNotPubliclyReadable() throws Exception {
		String tutorToken = signIn("9600000014", UserRole.TUTOR);
		String adminToken = signInAsAdmin("9600000015");

		mockMvc.perform(multipart("/api/v1/tutor/verification/ID")
				.file(pdf()).header("Authorization", "Bearer " + tutorToken))
				.andExpect(status().isOk());

		String queue = mockMvc.perform(get("/api/v1/admin/verifications/pending")
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String documentKey = JsonPath.read(queue, "$.content[0].documentUrl");
		assertThat(documentKey).startsWith("id-document/");

		// 404, not 403 — a 403 would confirm this person has an ID document on file.
		mockMvc.perform(get("/api/v1/public/files/" + documentKey))
				.andExpect(status().isNotFound());

		// Unauthenticated on the admin route is a 401, not a leak.
		mockMvc.perform(get("/api/v1/admin/files/" + documentKey))
				.andExpect(status().isUnauthorized());

		// And an admin can read it, which is the point of the whole route.
		mockMvc.perform(get("/api/v1/admin/files/" + documentKey)
						.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk());
	}

	// --- Helpers ------------------------------------------------------------------------------

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

	/**
	 * Admins cannot self-register — {@code AuthService} refuses the ADMIN role deliberately — so the
	 * account is created directly, the way a real one would be provisioned.
	 */
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
