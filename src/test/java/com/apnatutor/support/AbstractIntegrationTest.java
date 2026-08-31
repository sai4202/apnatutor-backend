package com.apnatutor.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the full application context and a real database.
 *
 * <p>This project has no Docker, so Testcontainers is unavailable (see
 * {@code docs/SOURCE_OF_TRUTH.md} ADR #5). Integration tests instead run against a real local
 * PostgreSQL database, {@code apnatutor_test}, created once by {@code scripts/db-setup.sql}.
 *
 * <p>The {@code test} profile points the datasource at that database and lets Flyway clean and
 * re-migrate it, so each run starts from a known-empty schema rather than inheriting state from the
 * last one. That database is disposable by definition — never point this profile at a database
 * whose contents you care about.
 *
 * <p>Extend this rather than repeating the annotations: it keeps the profile in one place, so the
 * day the test database setup changes, it changes once.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestFlywayConfig.class)
public abstract class AbstractIntegrationTest {
}
