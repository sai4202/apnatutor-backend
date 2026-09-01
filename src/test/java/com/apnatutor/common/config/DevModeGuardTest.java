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
		return new AppProperties(
				"http://localhost:3000",
				new AppProperties.Jwt(
						"a-secret-that-is-comfortably-longer-than-32-bytes",
						Duration.ofMinutes(15),
						Duration.ofDays(30)),
				new AppProperties.Otp(Duration.ofMinutes(10), 5, 5),
				new AppProperties.Sms(smsProvider),
				new AppProperties.Dev(devEnabled, "123456"));
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
	@DisplayName("dev mode off is fine in any configuration")
	void allowsProductionConfiguration() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatCode(() -> new DevModeGuard(props(false, "msg91"), prod).verify())
				.doesNotThrowAnyException();
	}
}
