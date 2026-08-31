package com.apnatutor.common.config;

import java.time.Clock;
import java.util.List;

import com.apnatutor.common.security.CurrentUserArgumentResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final CurrentUserArgumentResolver currentUserArgumentResolver;

	public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver) {
		this.currentUserArgumentResolver = currentUserArgumentResolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(currentUserArgumentResolver);
	}

	/**
	 * Time as an injectable dependency rather than {@code Instant.now()} scattered through services.
	 *
	 * <p>Everything in this product is time-sensitive — OTP expiry, token lifetimes, credit expiry,
	 * requirement expiry, the refund window. Testing those against a real clock means either sleeping
	 * or asserting loosely. With an injected {@code Clock}, a test can state exactly what "now" is.
	 *
	 * <p>UTC deliberately: timestamps are stored UTC and rendered in Asia/Kolkata at the edge
	 * (SOURCE_OF_TRUTH.md §5).
	 */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
