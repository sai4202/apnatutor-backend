package com.apnatutor.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Rebuilds the test schema from scratch on every test run.
 *
 * <p><strong>Why this exists.</strong> Flyway records a checksum for each applied migration and
 * refuses to start if a file has changed since. That protection is exactly right for a real
 * database, but during development a migration is edited many times before it is ever committed, and
 * the test database would otherwise wedge on the first edit and need manual intervention.
 *
 * <p>Flyway's old {@code cleanOnValidationError} option would have covered this, but it was removed
 * in Flyway 9/10 — setting it in {@code application-test.yml} does nothing at all, silently. Doing
 * it explicitly here is both functional and obvious.
 *
 * <p>Cleaning also gives every run the property {@code M6-07} depends on: the schema is built by
 * replaying every migration from V1, so a migration that only works as an increment against an
 * existing local database fails here rather than in production.
 *
 * <p><strong>Only ever active in tests.</strong> {@code clean} is destructive, and
 * {@code clean-disabled} stays true for the dev and production datasources.
 */
@TestConfiguration
public class TestFlywayConfig {

	@Bean
	FlywayMigrationStrategy cleanAndMigrate() {
		return flyway -> {
			flyway.clean();
			flyway.migrate();
		};
	}
}
