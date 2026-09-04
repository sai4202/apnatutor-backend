package com.apnatutor.ratelimit;

import com.apnatutor.common.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Puts the rate limiter in front of Spring Security.
 *
 * <h2>Why a registration bean and not {@code @Component} plus {@code @Order}</h2>
 *
 * <p>Boot auto-registers any {@code Filter} bean at {@code LOWEST_PRECEDENCE} and ignores
 * {@code @Order} while doing it. A filter meant to run first would end up running last, silently —
 * it would still work for authenticated traffic and quietly fail to see exactly the anonymous
 * requests it was added for. Constructing the filter with {@code new} inside this method keeps it
 * from being a bean at all, so there is one registration and its order is the one written here.
 */
@Configuration
public class RateLimitConfig {

	@Bean
	FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
			RateLimiter limiter, ObjectMapper json, AppProperties properties) {

		FilterRegistrationBean<RateLimitFilter> registration =
				new FilterRegistrationBean<>(new RateLimitFilter(limiter, json, properties));

		registration.setOrder(RateLimitFilter.ORDER);
		registration.addUrlPatterns("/api/*");
		return registration;
	}
}
