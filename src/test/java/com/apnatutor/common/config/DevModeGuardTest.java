package com.apnatutor.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests the control that stops development conveniences reaching production.
 *
 * <p>Worth testing precisely because it is a safety net: dev mode seeds accounts with a published
 * OTP and returns generated codes in API responses. If this guard silently stopped working, nothing
 * else would notice until someone else did.
 */
class DevModeGuardTest {

	private static AppProperties props(boolean devEnabled, String smsProvider) {
		return props(devEnabled, smsProvider, new AppProperties.Razorpay("", "", ""));
	}

	private static AppProperties props(
			boolean devEnabled, String smsProvider, AppProperties.Razorpay razorpay) {
		return new AppProperties(
				"http://localhost:3000",
				new AppProperties.Jwt(
						"a-secret-that-is-comfortably-longer-than-32-bytes",
						Duration.ofMinutes(15),
						Duration.ofDays(30)),
				new AppProperties.Otp(Duration.ofMinutes(10), 5, 5),
				new AppProperties.Sms(smsProvider),
				new AppProperties.Storage("local", "./uploads"),
				new AppProperties.Dev(devEnabled, "123456"),
				razorpay);
	}

	private static AppProperties.Razorpay configuredRazorpay() {
		return new AppProperties.Razorpay("rzp_test_key", "secret", "webhook-secret");
	}

	@Test
	@DisplayName("dev mode with the console SMS stub is allowed")
	void allowsDevModeLocally() {
		assertThatCode(() -> new DevModeGuard(props(true, "console"), new MockEnvironment()).verify())
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("dev mode with a real SMS provider refuses to start")
	void refusesDevModeWithRealSmsProvider() {
		assertThatThrownBy(() -> new DevModeGuard(props(true, "msg91"), new MockEnvironment()).verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REFUSING TO START")
				.hasMessageContaining("msg91");
	}

	@Test
	@DisplayName("dev mode under the prod profile refuses to start")
	void refusesDevModeInProdProfile() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> new DevModeGuard(props(true, "console"), prod).verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REFUSING TO START");
	}

	@Test
	@DisplayName("a fully configured production setup starts")
	void allowsProductionConfiguration() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatCode(() -> new DevModeGuard(
				props(false, "msg91", configuredRazorpay()), prod).verify())
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("production without payment credentials refuses to start")
	void refusesProductionWithoutPaymentProvider() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		// The dangerous one. Dev mode is off and SMS is real, so nothing else about this
		// deployment looks wrong — but with no Razorpay keys the stub gateway is live and grants
		// credits for money that never moved. Nothing would surface that until the accounts were
		// reconciled, which is exactly why it has to fail at startup.
		assertThatThrownBy(() -> new DevModeGuard(props(false, "msg91"), prod).verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REFUSING TO START")
				.hasMessageContaining("Razorpay");
	}

	@Test
	@DisplayName("missing payment credentials are fine outside production")
	void allowsStubGatewayLocally() {
		// The whole point of the stub: the purchase flow is buildable and testable before anyone
		// opens a Razorpay account.
		assertThatCode(() -> new DevModeGuard(props(true, "console"), new MockEnvironment()).verify())
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a Razorpay key id without a secret does not count as configured")
	void halfConfiguredRazorpayIsNotConfigured() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		// A key id alone signs nothing. Treating it as configured would wire the real gateway with
		// no way to authenticate, which fails later and far less clearly than this does.
		assertThatThrownBy(() -> new DevModeGuard(
				props(false, "msg91", new AppProperties.Razorpay("rzp_test_key", "", "")), prod)
				.verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REFUSING TO START");
	}
}
