package com.apnatutor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
// Boot 4 moved this out of org.springframework.boot.test.autoconfigure.web.servlet.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import com.jayway.jsonpath.JsonPath;

/**
 * End-to-end auth tests against a real PostgreSQL database.
 *
 * <p>Not transactional: the OTP attempt counter is committed in its own transaction (see {@link
 * OtpAttemptRecorder}), so a test-managed rollback would hide exactly the behaviour that most needs
 * proving. Each test uses its own phone number instead, which keeps them independent without
 * needing isolation.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingSmsSender sms;

	@Autowired
	private UserRepository users;

	@BeforeEach
	void resetSms() {
		sms.clear();
	}

	// --- Sign-up and sign-in ------------------------------------------------------------------

	@Test
	@DisplayName("a new number can request a code, verify it, and gets an account plus tokens")
	void fullSignUpFlow() throws Exception {
		String phone = "9800000001";

		requestOtp(phone).andExpect(status().isOk());

		String code = sms.latestCodeFor("+91" + phone).orElseThrow();
		assertThat(code).hasSize(6);

		MvcResult result = verifyOtp(phone, code, "STUDENT")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.newAccount").value(true))
				.andExpect(jsonPath("$.user.role").value("STUDENT"))
				.andExpect(jsonPath("$.user.phone").value("+91" + phone))
				// The refresh token must never appear in the body — only in an HttpOnly cookie.
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(cookie().exists(RefreshCookie.COOKIE_NAME))
				.andExpect(cookie().httpOnly(RefreshCookie.COOKIE_NAME, true))
				.andReturn();

		assertThat(users.existsByPhone("+91" + phone)).isTrue();
		assertThat(accessToken(result)).isNotBlank();
	}

	@Test
	@DisplayName("signing in again reuses the existing account rather than creating a second one")
	void secondSignInDoesNotCreateAnotherAccount() throws Exception {
		String phone = "9800000002";

		signIn(phone, UserRole.TUTOR);
		long countAfterFirst = users.count();

		requestOtp(phone).andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		verifyOtp(phone, code, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.newAccount").value(false))
				.andExpect(jsonPath("$.user.role").value("TUTOR"));

		assertThat(users.count()).isEqualTo(countAfterFirst);
	}

	@Test
	@DisplayName("differently formatted versions of one number resolve to a single account")
	void formattingVariationsAreOneAccount() throws Exception {
		signIn("9800000003", UserRole.STUDENT);
		long countAfterFirst = users.count();

		// Same human, typed with the country code and spaces.
		requestOtp("+91 98000 00003").andExpect(status().isOk());
		String code = sms.latestCodeFor("+919800000003").orElseThrow();
		verifyOtp("+91 98000 00003", code, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.newAccount").value(false));

		assertThat(users.count()).isEqualTo(countAfterFirst);
	}

	// --- OTP security -------------------------------------------------------------------------

	@Test
	@DisplayName("a wrong code is rejected")
	void wrongCodeRejected() throws Exception {
		String phone = "9800000004";
		requestOtp(phone).andExpect(status().isOk());

		verifyOtp(phone, "000000", "STUDENT")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	@DisplayName("the attempt cap actually holds — the counter must survive the rejection")
	void attemptCapIsEnforced() throws Exception {
		// This is the test that catches the rollback bug: recording a failed attempt and then
		// throwing are contradictory demands on one transaction. If the increment is rolled back
		// with the rejection, the counter never advances, the cap never fires, and a six-digit
		// code is brute-forceable. See OtpAttemptRecorder.
		String phone = "9800000005";
		requestOtp(phone).andExpect(status().isOk());
		String realCode = sms.latestCodeFor("+91" + phone).orElseThrow();

		String wrongCode = realCode.equals("111111") ? "222222" : "111111";

		// Config allows 5 attempts.
		for (int i = 0; i < 5; i++) {
			verifyOtp(phone, wrongCode, "STUDENT")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("OTP_INVALID"));
		}

		// Sixth attempt is refused outright, and crucially the CORRECT code no longer works —
		// proving the cap is real and not merely a different error message.
		verifyOtp(phone, wrongCode, "STUDENT")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_ATTEMPTS_EXCEEDED"));

		verifyOtp(phone, realCode, "STUDENT")
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_ATTEMPTS_EXCEEDED"));

		assertThat(users.existsByPhone("+91" + phone)).isFalse();
	}

	@Test
	@DisplayName("a correct code cannot be replayed")
	void codeIsSingleUse() throws Exception {
		String phone = "9800000006";
		requestOtp(phone).andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		verifyOtp(phone, code, "STUDENT").andExpect(status().isOk());

		verifyOtp(phone, code, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	@DisplayName("requesting a new code retires the previous one")
	void newCodeInvalidatesOld() throws Exception {
		String phone = "9800000007";
		requestOtp(phone).andExpect(status().isOk());
		String firstCode = sms.latestCodeFor("+91" + phone).orElseThrow();

		requestOtp(phone).andExpect(status().isOk());

		verifyOtp(phone, firstCode, "STUDENT")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	@DisplayName("the hourly send cap stops the endpoint being used to run up an SMS bill")
	void sendRateLimitIsEnforced() throws Exception {
		String phone = "9800000008";

		for (int i = 0; i < 5; i++) {
			requestOtp(phone).andExpect(status().isOk());
		}

		requestOtp(phone)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_SEND_LIMIT_EXCEEDED"));

		assertThat(sms.countFor("+91" + phone)).isEqualTo(5);
	}

	@Test
	@DisplayName("the response does not reveal whether a number is registered")
	void doesNotLeakAccountExistence() throws Exception {
		String registered = "9800000009";
		signIn(registered, UserRole.STUDENT);
		sms.clear();

		String registeredBody = requestOtp(registered)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		String unregisteredBody = requestOtp("9800000010")
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		// Identical responses are the whole defence. If they differed, this endpoint would be a
		// free oracle for discovering which phone numbers hold accounts.
		assertThat(registeredBody).isEqualTo(unregisteredBody);
	}

	@Test
	@DisplayName("an invalid phone number is rejected before any SMS is sent")
	void invalidPhoneRejected() throws Exception {
		requestOtp("1234567890")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(sms.countFor("+911234567890")).isZero();
	}

	@Test
	@DisplayName("nobody can self-register as an admin")
	void cannotSelfRegisterAsAdmin() throws Exception {
		String phone = "9800000011";
		requestOtp(phone).andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		verifyOtp(phone, code, "ADMIN")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(users.existsByPhone("+91" + phone)).isFalse();
	}

	@Test
	@DisplayName("creating an account requires choosing a role")
	void newAccountRequiresRole() throws Exception {
		String phone = "9800000012";
		requestOtp(phone).andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();

		verifyOtp(phone, code, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// --- Access tokens ------------------------------------------------------------------------

	@Test
	@DisplayName("a valid access token identifies the caller at /me")
	void accessTokenWorks() throws Exception {
		String phone = "9800000013";
		MvcResult session = signIn(phone, UserRole.TUTOR);

		mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + accessToken(session)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value("+91" + phone))
				.andExpect(jsonPath("$.role").value("TUTOR"));
	}

	@Test
	@DisplayName("/me without a token is rejected in the standard error shape")
	void meRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("a tampered token is rejected")
	void tamperedTokenRejected() throws Exception {
		MvcResult session = signIn("9800000014", UserRole.STUDENT);
		String tampered = accessToken(session) + "x";

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized());
	}

	// --- Refresh rotation ---------------------------------------------------------------------

	@Test
	@DisplayName("refresh requires the CSRF header, since the cookie is sent automatically")
	void refreshRequiresCsrfHeader() throws Exception {
		MvcResult session = signIn("9800000015", UserRole.STUDENT);

		mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie(session)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("refresh issues a new access token and rotates the refresh cookie")
	void refreshRotates() throws Exception {
		MvcResult session = signIn("9800000016", UserRole.STUDENT);
		Cookie original = refreshCookie(session);

		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(original)
						.header(RefreshCookie.CSRF_HEADER, "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();

		assertThat(refreshCookie(refreshed).getValue()).isNotEqualTo(original.getValue());
	}

	@Test
	@DisplayName("reusing a rotated refresh token revokes the whole family")
	void refreshTokenReuseRevokesFamily() throws Exception {
		MvcResult session = signIn("9800000017", UserRole.STUDENT);
		Cookie original = refreshCookie(session);

		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(original)
						.header(RefreshCookie.CSRF_HEADER, "1"))
				.andExpect(status().isOk())
				.andReturn();
		Cookie rotated = refreshCookie(refreshed);

		// Replaying the old token is the signature of theft.
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(original)
						.header(RefreshCookie.CSRF_HEADER, "1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

		// ...and the consequence is that the legitimate current token dies too. That is the
		// intended trade: the real user re-authenticates with an OTP, the thief cannot.
		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(rotated)
						.header(RefreshCookie.CSRF_HEADER, "1"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("logout revokes the session and clears the cookie")
	void logoutRevokes() throws Exception {
		MvcResult session = signIn("9800000018", UserRole.STUDENT);
		Cookie refresh = refreshCookie(session);

		mockMvc.perform(post("/api/v1/auth/logout").cookie(refresh))
				.andExpect(status().isOk())
				.andExpect(cookie().maxAge(RefreshCookie.COOKIE_NAME, 0));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(refresh)
						.header(RefreshCookie.CSRF_HEADER, "1"))
				.andExpect(status().isUnauthorized());
	}

	// --- Helpers ------------------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions requestOtp(String phone)
			throws Exception {
		return mockMvc.perform(post("/api/v1/auth/otp/request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phone\":\"" + phone + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions verifyOtp(
			String phone, String code, String role) throws Exception {
		String body = role == null
				? "{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)
				: "{\"phone\":\"%s\",\"code\":\"%s\",\"role\":\"%s\"}".formatted(phone, code, role);
		return mockMvc.perform(post("/api/v1/auth/otp/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	/** Requests a code, reads it from the captured SMS, and verifies it. */
	private MvcResult signIn(String phone, UserRole role) throws Exception {
		requestOtp(phone).andExpect(status().isOk());
		String code = sms.latestCodeFor("+91" + phone).orElseThrow();
		return verifyOtp(phone, code, role.name()).andExpect(status().isOk()).andReturn();
	}

	private String accessToken(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
	}

	private Cookie refreshCookie(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie(RefreshCookie.COOKIE_NAME);
		assertThat(cookie).as("refresh cookie").isNotNull();
		return cookie;
	}
}
