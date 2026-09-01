package com.apnatutor.common.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to start the application if development conveniences are enabled somewhere they must not
 * be.
 *
 * <p>Dev mode seeds accounts with a fixed, published OTP and returns generated codes in API
 * responses. Left on in production that is not a bug, it is an unauthenticated login for anyone who
 * reads the README.
 *
 * <p>A comment saying "remember to disable this" is not a control. Two independent conditions are
 * checked, and either one failing stops the process:
 *
 * <ol>
 *   <li>Dev mode with a <em>real</em> SMS provider configured — the combination only makes sense if
 *       someone is midway through switching to production and has forgotten this flag.
 *   <li>Dev mode with a {@code prod} profile active.
 * </ol>
 *
 * <p>Failing at startup is the point. A misconfiguration that crashes on deploy gets fixed in
 * minutes; one that boots quietly gets discovered by whoever finds it first.
 */
@Configuration
public class DevModeGuard {

	private static final Logger log = LoggerFactory.getLogger(DevModeGuard.class);

	private final AppProperties properties;
	private final Environment environment;

	public DevModeGuard(AppProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	@PostConstruct
	void verify() {
		if (!properties.dev().enabled()) {
			return;
		}

		if (!properties.sms().isConsoleStub()) {
			throw new IllegalStateException("""

					REFUSING TO START: apnatutor.dev.enabled=true with a real SMS provider \
					(apnatutor.sms.provider=%s).

					Dev mode seeds accounts with a published fixed OTP and returns generated \
					codes in API responses. With a real provider configured this looks like a \
					production deployment, and those conveniences would be an open door.

					Set apnatutor.dev.enabled=false.""".formatted(properties.sms().provider()));
		}

		for (String profile : environment.getActiveProfiles()) {
			if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
				throw new IllegalStateException(
						"REFUSING TO START: apnatutor.dev.enabled=true with the '" + profile
								+ "' profile active. Set apnatutor.dev.enabled=false.");
			}
		}

		log.warn("""

				  ┌──────────────────────────────────────────────────────────────
				  │ DEV MODE IS ON — never enable this in production
				  │
				  │ · Test accounts are seeded with a fixed, published OTP
				  │ · Generated OTPs are returned in API responses
				  │ · SMS is logged to this console, not sent
				  └──────────────────────────────────────────────────────────────""");
	}
}
