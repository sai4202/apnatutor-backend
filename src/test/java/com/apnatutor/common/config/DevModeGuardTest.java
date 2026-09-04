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

	@Test
	@DisplayName("production with a console stub still wired up refuses to start")
	void refusesProductionWithConsoleStubs() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		// Each of these runs without error and fails silently, which is why it is a startup check
		// rather than something to notice later: console mail logs receipts instead of sending
		// them, and local storage loses a tutor's ID documents on the next redeploy.
		assertThatThrownBy(() -> new DevModeGuard(
						props(false, "msg91", configuredRazorpay(), "console", "s3"), prod).verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("apnatutor.mail.provider=console");

		assertThatThrownBy(() -> new DevModeGuard(
						props(false, "msg91", configuredRazorpay(), "ses", "local"), prod).verify())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("apnatutor.storage.provider=local");
	}

	@Test
	@DisplayName("console stubs are fine outside a production profile")
	void allowsStubsInDevelopment() {
		assertThatCode(() -> new DevModeGuard(
						props(true, "console", configuredRazorpay(), "console", "local"),
						new MockEnvironment()).verify())
				.doesNotThrowAnyException();
	}

	private static AppProperties props(boolean devEnabled, String smsProvider) {
		return props(devEnabled, smsProvider, new AppProperties.Razorpay("", "", ""));
	}

	private static AppProperties props(
			boolean devEnabled, String smsProvider, AppProperties.Razorpay razorpay) {
		return props(devEnabled, smsProvider, razorpay, "console", "local");
	}

	/**
	 * The full shape, for the tests that care which stubs are wired up.
	 *
	 * <p>The mail and storage providers gained their own guard at M6-10.5, so a fixture that
	 * hard-coded the console stubs stopped being a valid production configuration — which is the
	 * check working, not the test breaking.
	 */
	private static AppProperties props(
			boolean devEnabled,
			String smsProvider,
			AppProperties.Razorpay razorpay,
			String mailProvider,
			String storageProvider) {
		return new AppProperties(
				"http://localhost:3000",
				new AppProperties.Jwt(
						"a-secret-that-is-comfortably-longer-than-32-bytes",
						Duration.ofMinutes(15),
						Duration.ofDays(30)),
				new AppProperties.Otp(Duration.ofMinutes(10), 5, 5),
				new AppProperties.Sms(smsProvider),
				new AppProperties.Mail(mailProvider),
				new AppProperties.Storage(
						storageProvider, "./uploads", "apnatutor-uploads", "ap-south-1",
						null, null, null),
				new AppProperties.Dev(devEnabled, "123456"),
				// Irrelevant to what this test asserts; any valid values will do.
				new AppProperties.RateLimit(20, 120, 3000, 300, 30),
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

		// Everything real: no dev mode, a genuine SMS provider, payments configured, and neither
		// the console mailer nor local disk storage left behind.
		assertThatCode(() -> new DevModeGuard(
				props(false, "msg91", configuredRazorpay(), "ses", "s3"), prod).verify())
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
