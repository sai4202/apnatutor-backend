package com.apnatutor.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.support.AbstractIntegrationTest;
import com.apnatutor.support.RecordingSmsSender;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rate limiting end to end — {@code M5-07}.
 *
 * <p>{@code @TestPropertySource} rather than the suite-wide values, so this class gets its own
 * context with limits low enough to reach in a test. The rest of the suite runs with the limiter
 * effectively disabled, because every test drives MockMvc from the same loopback address.
 */
@AutoConfigureMockMvc
@Import(RecordingSmsSender.Config.class)
@TestPropertySource(properties = {
		"apnatutor.rate-limit.auth-per-minute=3",
		"apnatutor.rate-limit.unlocks-per-hour-per-tutor=2",
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LeadUnlockService unlockService;

	@Autowired
	private RequirementRepository requirements;

	@Autowired
	private CreditLedger ledger;

	@Autowired
	private UserRepository users;

	@Autowired
	private SubjectRepository subjects;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("the auth endpoint returns 429 with Retry-After once the allowance is spent")
	void authIsLimitedWithRetryAfter() throws Exception {
		// Three permits, and each request uses a different phone number so nothing is refused by
		// the per-phone OTP cap instead — the point is that the IP limit is what fires.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(requestCode("198.51.100.7", freshPhone()))
					.andExpect(status().isOk());
		}

		mockMvc.perform(requestCode("198.51.100.7", freshPhone()))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	@DisplayName("the 429 says how long to wait, in seconds")
	void retryAfterIsAUsefulNumber() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(requestCode("198.51.100.8", freshPhone()));
		}

		String retryAfter = mockMvc.perform(requestCode("198.51.100.8", freshPhone()))
				.andExpect(status().isTooManyRequests())
				.andReturn().getResponse().getHeader("Retry-After");

		assertThat(retryAfter).isNotNull();
		assertThat(Long.parseLong(retryAfter))
				.as("a client told to back off by an unparseable amount retries immediately")
				.isBetween(1L, 60L);
	}

	@Test
	@DisplayName("a tutor's unlock allowance is per tutor, not per address")
	void unlockLimitIsPerTutor() {
		Long first = fundedTutor(50);
		Long second = fundedTutor(50);

		unlockService.unlock(postRequirement().getId(), first, null);
		unlockService.unlock(postRequirement().getId(), first, null);

		Long thirdRequirement = postRequirement().getId();
		assertThat(catchUnlock(thirdRequirement, first))
				.as("two permits, so the third new unlock is refused")
				.isInstanceOf(RateLimitedException.class);

		// Same process, same address, different tutor.
		unlockService.unlock(postRequirement().getId(), second, null);
	}

	@Test
	@DisplayName("replaying an unlock a tutor already owns is never rate limited")
	void replayedUnlocksDoNotCountAgainstTheAllowance() {
		Long tutorId = fundedTutor(50);
		Requirement requirement = postRequirement();

		LeadUnlock bought = unlockService.unlock(requirement.getId(), tutorId, null);
		int balanceAfterBuying = ledger.balanceOf(tutorId);

		// Far more replays than the allowance of two.
		for (int i = 0; i < 10; i++) {
			LeadUnlock replayed = unlockService.unlock(requirement.getId(), tutorId, null);
			assertThat(replayed.getId()).isEqualTo(bought.getId());
		}

		assertThat(ledger.balanceOf(tutorId))
				.as("THE POINT: a tutor on a patchy connection retrying the same unlock buys "
						+ "nothing new, so it must neither charge them again nor count against a "
						+ "limit. Refusing the retry would turn a flaky network into a lost credit")
				.isEqualTo(balanceAfterBuying);

		// And the allowance is genuinely untouched: one new unlock still succeeds.
		unlockService.unlock(postRequirement().getId(), tutorId, null);
	}

	// --- Fixtures -------------------------------------------------------------------------------

	/**
	 * An OTP request from a named address.
	 *
	 * <p>Each test uses its own address. Both auth tests share one context and therefore one
	 * limiter, so without this the second to run would find the first had already spent the
	 * three permits — a failure that depends on test ordering, which is the worst kind.
	 * It also exercises the X-Forwarded-For path the filter relies on behind a proxy.
	 */
	private org.springframework.test.web.servlet.RequestBuilder requestCode(String ip, String phone) {
		return post("/api/v1/auth/otp/request")
				.header("X-Forwarded-For", ip)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phone\":\"" + phone + "\"}");
	}

	/** Runs an unlock and hands back whatever it threw, or null. */
	private Throwable catchUnlock(Long requirementId, Long tutorId) {
		try {
			unlockService.unlock(requirementId, tutorId, null);
			return null;
		} catch (ApiException e) {
			return e;
		}
	}

	private Requirement postRequirement() {
		User student = users.save(User.registerVerified(
				uniquePhone("9168"), UserRole.STUDENT, clock.instant()));

		return requirements.save(Requirement.post(
				student.getId(),
				subjects.findByLeafTrueAndActiveTrueOrderByDisplayOrderAsc().getFirst().getId(),
				null, null, null,
				"ONLINE", 300_000L, "PER_MONTH", null, null, null,
				"Rate limit test requirement",
				5, 5,
				clock.instant().plus(Duration.ofDays(30))));
	}

	private Long fundedTutor(int credits) {
		User tutor = users.save(User.registerVerified(
				uniquePhone("9167"), UserRole.TUTOR, clock.instant()));
		ledger.grant(tutor.getId(), credits, CreditReason.ADMIN_ADJUSTMENT, "TEST", null, null);
		return tutor.getId();
	}

	/** A local-format number, for the OTP endpoint. */
	private static String freshPhone() {
		return uniquePhone("9166").substring(3);
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
