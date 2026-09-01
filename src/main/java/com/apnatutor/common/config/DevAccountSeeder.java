package com.apnatutor.common.config;

import java.time.Clock;
import java.util.List;

import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import com.apnatutor.user.domain.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds one account per role so the application is usable with no SMS provider.
 *
 * <p>Guarded by {@code apnatutor.dev.enabled}, and {@link DevModeGuard} refuses to start at all if
 * that is set anywhere it should not be.
 *
 * <p>Seeded through an {@code ApplicationRunner} rather than a Flyway migration on purpose. A
 * migration runs in every environment it is deployed to, so accounts with a published password
 * would reach production the first time anyone deployed. This runs only when a flag says so.
 *
 * <p>The numbers are drawn from the {@code 99999 0000x} range — real, valid-format Indian mobile
 * numbers that are extremely unlikely to belong to an actual person who might otherwise receive our
 * SMS.
 */
@Configuration
@ConditionalOnProperty(name = "apnatutor.dev.enabled", havingValue = "true")
public class DevAccountSeeder {

	private static final Logger log = LoggerFactory.getLogger(DevAccountSeeder.class);

	/** Phone numbers here bypass SMS entirely and accept the fixed dev code. */
	public static final String STUDENT_PHONE = "+919999900001";
	public static final String TUTOR_PHONE = "+919999900002";
	public static final String ADMIN_PHONE = "+919999900003";

	private record SeedAccount(String phone, UserRole role, String label) {
	}

	private static final List<SeedAccount> ACCOUNTS = List.of(
			new SeedAccount(STUDENT_PHONE, UserRole.STUDENT, "Student / Parent"),
			new SeedAccount(TUTOR_PHONE, UserRole.TUTOR, "Tutor"),
			new SeedAccount(ADMIN_PHONE, UserRole.ADMIN, "Admin"));

	/** True if this number is a seeded dev account. Used to bypass SMS and rate limits. */
	public static boolean isDevAccount(String canonicalPhone) {
		return ACCOUNTS.stream().anyMatch(a -> a.phone().equals(canonicalPhone));
	}

	@Bean
	ApplicationRunner seedDevAccounts(
			UserRepository users, Clock clock, AppProperties properties) {
		return args -> createAccounts(users, clock, properties);
	}

	@Transactional
	void createAccounts(UserRepository users, Clock clock, AppProperties properties) {
		for (SeedAccount account : ACCOUNTS) {
			// Idempotent: the app restarts constantly in development, and re-seeding must not
			// fail on the unique phone index or reset an account someone is mid-way through using.
			if (users.existsByPhone(account.phone())) {
				continue;
			}
			users.save(User.registerVerified(account.phone(), account.role(), clock.instant()));
		}

		String code = properties.dev().testAccountCode();
		log.warn("""

				  ┌──────────────────────────────────────────────────────────────
				  │ TEST ACCOUNTS — sign in at /login, no SMS needed
				  │
				  │   Student / Parent   9999900001
				  │   Tutor              9999900002
				  │   Admin              9999900003
				  │
				  │   OTP for all three: {}
				  │
				  │ Any other number gets a random code, printed to this console.
				  └──────────────────────────────────────────────────────────────""", code);
	}
}
