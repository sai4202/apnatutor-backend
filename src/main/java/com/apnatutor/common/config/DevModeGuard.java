package com.apnatutor.common.config;

import java.util.ArrayList;
import java.util.List;

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
 *   <li>A {@code prod} profile with <em>no payment provider configured</em>, checked independently
 *       of dev mode. That combination means the stub gateway is live, granting credits for money
 *       that never moved — and unlike the others it produces a deployment that looks entirely
 *       healthy until someone reconciles the accounts.
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
		verifyPaymentsAreReal();
		verifyNoStubsInProduction();

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

		if (productionProfile() != null) {
			throw new IllegalStateException(
					"REFUSING TO START: apnatutor.dev.enabled=true with the '" + productionProfile()
							+ "' profile active. Set apnatutor.dev.enabled=false.");
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

	/**
	 * Refuses to run in production without a payment provider.
	 *
	 * <p>Checked outside the dev-mode block on purpose. The other two guards catch a deployment that
	 * still has development conveniences on; this one catches a deployment that looks completely
	 * correct — real SMS, dev mode off — but has no Razorpay keys, so {@code StubPaymentGateway} is
	 * quietly issuing credits for money that never arrived. Nothing else would surface that until
	 * the accounts were reconciled.
	 */
	private void verifyPaymentsAreReal() {
		String profile = productionProfile();
		if (profile == null || properties.razorpay().isConfigured()) {
			return;
		}

		throw new IllegalStateException("""

				REFUSING TO START: the '%s' profile is active but no Razorpay credentials are set.

				Without them the stub payment gateway is used, which grants credits without \
				taking any money. That is not a visible failure — the application would run \
				normally and hand out free credits until somebody reconciled the accounts.

				Set apnatutor.razorpay.key-id and apnatutor.razorpay.key-secret \
				(RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET).""".formatted(profile));
	}

	/**
	 * Refuses a production profile that is still wired to a console stub — {@code M6-10.5}.
	 *
	 * <p>Each of these is a stub that <em>works</em>, which is what makes them dangerous. The
	 * console SMS sender logs the OTP instead of sending it, so every user is locked out and the
	 * codes are sitting in a log file. The console mailer does the same with receipts. Local file
	 * storage accepts uploads and loses them on the next redeploy, taking a tutor's ID documents
	 * with them.
	 *
	 * <p>None of the three produces an error at startup or an exception at runtime. They produce a
	 * deployment that looks healthy and is quietly broken in a way the first affected user
	 * discovers — which is exactly the class of failure a startup check is for.
	 */
	private void verifyNoStubsInProduction() {
		String profile = productionProfile();
		if (profile == null) {
			return;
		}

		List<String> stubs = new ArrayList<>();
		if (properties.sms().isConsoleStub()) {
			stubs.add("apnatutor.sms.provider=console — OTPs would be logged, not sent, "
					+ "so nobody could sign in and every code would be in the logs "
					+ "(set APNATUTOR_SMS_PROVIDER)");
		}
		if ("console".equalsIgnoreCase(properties.mail().provider())) {
			stubs.add("apnatutor.mail.provider=console — receipts and notifications would be "
					+ "logged, not sent (set APNATUTOR_MAIL_PROVIDER)");
		}
		if ("local".equalsIgnoreCase(properties.storage().provider())) {
			stubs.add("apnatutor.storage.provider=local — uploads go to local disk, which most "
					+ "hosts wipe on redeploy and no second instance can read, taking tutors' "
					+ "ID documents with it (set APNATUTOR_STORAGE_PROVIDER=s3)");
		}

		if (stubs.isEmpty()) {
			return;
		}

		throw new IllegalStateException("""

				REFUSING TO START: the '%s' profile is active with %d console stub(s) still \
				configured.

				%s

				Every one of these runs without error and fails silently, which is why this \
				is checked at startup rather than left to be noticed.""".formatted(
				profile, stubs.size(), String.join("\n\n", stubs)));
	}

	/** The active production-like profile, or null if none is. */
	private String productionProfile() {
		for (String profile : environment.getActiveProfiles()) {
			if (profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production")) {
				return profile;
			}
		}
		return null;
	}
}
