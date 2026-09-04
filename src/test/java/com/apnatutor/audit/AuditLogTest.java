package com.apnatutor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.audit.domain.AuditEntry;
import com.apnatutor.audit.domain.AuditOutcome;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The audit log — {@code M5-08}.
 *
 * <p>Driven through MockMvc rather than by calling services, because the guarantee being tested is
 * the one the interceptor provides: an entry exists <em>because of the route</em>, not because the
 * service remembered. A test that called {@code UserAdminService.suspend} directly would pass with
 * the interceptor deleted.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class AuditLogTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuditRepository entries;

	@Autowired
	private UserRepository users;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Clock clock;

	@BeforeEach
	void setUp() {
		sms.clear();
	}

	@Test
	@DisplayName("suspending an account is recorded with who, why and what changed")
	void suspensionIsRecordedWithBeforeAndAfter() throws Exception {
		String token = adminToken();
		Long targetId = student();

		mockMvc.perform(post("/api/v1/admin/users/" + targetId + "/suspend")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Posting fake enquiries\"}"))
				.andExpect(status().isOk());

		AuditEntry entry = latestFor("USER", targetId);

		assertThat(entry.getAction()).isEqualTo("USER_SUSPENDED");
		assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCEEDED);
		assertThat(entry.getSummary()).isEqualTo("Posting fake enquiries");
		assertThat(entry.getActorRole()).isEqualTo(UserRole.ADMIN);
		assertThat(entry.getActorId()).isNotNull();
		assertThat(entry.getBeforeState()).contains("ACTIVE");
		assertThat(entry.getAfterState())
				.contains("SUSPENDED")
				.contains("Posting fake enquiries");
		assertThat(entry.getHttpMethod()).isEqualTo("POST");
		assertThat(entry.getHttpStatus()).isEqualTo(200);
		assertThat(entry.getCorrelationId())
				.as("ties the entry to its request's log lines")
				.isNotNull();
	}

	@Test
	@DisplayName("a refused action is recorded too — a run of them is somebody trying doors")
	void refusedActionsAreRecorded() throws Exception {
		String token = adminToken();
		Long otherAdmin = admin();

		mockMvc.perform(post("/api/v1/admin/users/" + otherAdmin + "/suspend")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Testing\"}"))
				.andExpect(status().isForbidden());

		AuditEntry entry = latest();

		assertThat(entry.getOutcome())
				.as("THE POINT: a log holding only successes cannot show an attempt")
				.isEqualTo(AuditOutcome.REFUSED);
		assertThat(entry.getHttpStatus()).isEqualTo(403);
		assertThat(entry.getPath()).endsWith("/suspend");
		assertThat(entry.getAction())
				.as("no service got far enough to name it, so the route identifies it — with the "
						+ "id normalised out, so this counts as one action rather than one per user")
				.isEqualTo("POST /admin/users/{id}/suspend");
	}

	@Test
	@DisplayName("a signed-in non-admin probing an admin route is recorded")
	void authenticatedProbingIsRecorded() throws Exception {
		String studentToken = studentToken();
		long before = entries.count();

		mockMvc.perform(post("/api/v1/admin/users/1/suspend")
						.header("Authorization", "Bearer " + studentToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Not mine to do\"}"))
				.andExpect(status().isForbidden());

		assertThat(entries.count()).isGreaterThan(before);

		AuditEntry entry = latest();
		assertThat(entry.getActorRole())
				.as("somebody holding a real token trying admin routes is the probe worth catching")
				.isEqualTo(UserRole.STUDENT);
		assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.REFUSED);
		assertThat(entry.getHttpStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("an anonymous request is turned away before the interceptor, and is not recorded")
	void anonymousRequestsAreNotRecorded() throws Exception {
		long before = entries.count();

		mockMvc.perform(post("/api/v1/admin/users/1/suspend")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"No token\"}"))
				.andExpect(status().isUnauthorized());

		assertThat(entries.count())
				.as("Asserted rather than wished for. anyRequest().authenticated() rejects this in "
						+ "the security filter chain, before any interceptor runs — so the audit "
						+ "log does not see it, and a comment claiming otherwise would be a lie "
						+ "somebody relies on. Anonymous probing belongs to the access log and to "
						+ "the rate limiting in M5-07. Debt T19.")
				.isEqualTo(before);
	}

	@Test
	@DisplayName("reading enquiries is audited; working a queue is not")
	void sensitiveReadsAreAuditedAndRoutineOnesAreNot() throws Exception {
		String token = adminToken();

		long before = entries.count();
		mockMvc.perform(get("/api/v1/admin/reviews/pending")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		assertThat(entries.count())
				.as("an entry per queue poll would bury the entries that matter under noise, "
						+ "which is a way of losing an audit log without deleting anything")
				.isEqualTo(before);

		mockMvc.perform(get("/api/v1/admin/requirements")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		AuditEntry entry = latest();
		assertThat(entry.getHttpMethod()).isEqualTo("GET");
		assertThat(entry.getPath())
				.as("THE POINT (debt T18): this is the only projection carrying a student's own "
						+ "phone number, so reading it is itself the sensitive act")
				.endsWith("/admin/requirements");
	}

	@Test
	@DisplayName("the log cannot be edited or deleted, by anything")
	void theLogIsAppendOnly() throws Exception {
		String token = adminToken();
		mockMvc.perform(post("/api/v1/admin/users/" + student() + "/suspend")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Something to edit\"}"))
				.andExpect(status().isOk());

		Long id = latest().getId();

		assertThatThrownBy(() -> jdbc.update(
						"UPDATE audit_log SET summary = 'nothing happened' WHERE id = ?", id))
				.as("an entry the application can rewrite proves nothing, and an UPDATE is the "
						+ "first thing anyone covering their tracks reaches for")
				.hasMessageContaining("append-only");

		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", id))
				.hasMessageContaining("append-only");
	}

	@Test
	@DisplayName("a credit grant records the balance it landed on top of")
	void creditGrantsRecordTheBalance() throws Exception {
		String token = adminToken();
		Long tutorId = tutor();

		mockMvc.perform(post("/api/v1/admin/credits/grant")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"tutorUserId":%d,"credits":25,"reason":"Goodwill after a support issue"}"""
								.formatted(tutorId)))
				.andExpect(status().isOk());

		AuditEntry entry = latestFor("USER", tutorId);

		assertThat(entry.getAction()).isEqualTo("CREDITS_GRANTED");
		assertThat(entry.getSummary()).isEqualTo("Goodwill after a support issue");
		assertThat(entry.getBeforeState()).contains("\"balance\": 0");
		assertThat(entry.getAfterState())
				.as("the ledger proves the credits arrived; only this says what they landed on")
				.contains("\"balance\": 25");
	}

	// --- Fixtures -------------------------------------------------------------------------------

	/** The newest entry overall. Ordered by id, not timestamp — two writes can share a millisecond. */
	private AuditEntry latest() {
		List<AuditEntry> all = new ArrayList<>(entries.findAll());
		all.sort((a, b) -> Long.compare(b.getId(), a.getId()));
		assertThat(all).as("something should have been recorded").isNotEmpty();
		return all.getFirst();
	}

	/** The newest entry against one target, so a shared test database cannot confuse the assertion. */
	private AuditEntry latestFor(String targetType, Long targetId) {
		List<AuditEntry> matching = entries.findAll().stream()
				.filter(entry -> targetType.equals(entry.getTargetType()))
				.filter(entry -> targetId.equals(entry.getTargetId()))
				.sorted((a, b) -> Long.compare(b.getId(), a.getId()))
				.toList();

		assertThat(matching).as("no entry for %s#%s", targetType, targetId).isNotEmpty();
		return matching.getFirst();
	}

	private Long student() {
		return users.save(User.registerVerified(
				uniquePhone("9178"), UserRole.STUDENT, clock.instant())).getId();
	}

	private Long tutor() {
		return users.save(User.registerVerified(
				uniquePhone("9177"), UserRole.TUTOR, clock.instant())).getId();
	}

	private Long admin() {
		return users.save(User.registerVerified(
				uniquePhone("9176"), UserRole.ADMIN, clock.instant())).getId();
	}

	/** A signed-in student — used to prove that a real token on an admin route is recorded. */
	private String studentToken() throws Exception {
		return signIn(uniquePhone("9174").substring(3), "STUDENT");
	}

	/**
	 * Signs in a fresh admin through the real OTP flow, so the token carries real claims.
	 *
	 * <p>The account is created directly first. OTP verify refuses to self-register an ADMIN
	 * (AuthService line 88), which is right — an endpoint anyone can reach must not mint admins —
	 * so the only honest way to get an admin token in a test is to seed the account and sign in.
	 */
	private String adminToken() throws Exception {
		String phone = uniquePhone("9175").substring(3);
		users.save(User.registerVerified("+91" + phone, UserRole.ADMIN, clock.instant()));

		// The requested role is ignored for an account that already exists, so STUDENT here
		// still yields an admin token.
		return signIn(phone, "STUDENT");
	}

	private String signIn(String phone, String requestedRole) throws Exception {
		mockMvc.perform(post("/api/v1/auth/otp/request")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isOk());

		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		String body = mockMvc.perform(post("/api/v1/auth/otp/verify")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"%s\",\"code\":\"%s\",\"role\":\"%s\"}"
								.formatted(phone, code, requestedRole)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		return JsonPath.read(body, "$.accessToken");
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
