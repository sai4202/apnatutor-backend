package com.apnatutor;

import com.apnatutor.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the Spring context starts, the datasource connects, and Flyway migrates cleanly.
 *
 * <p>Small, but it is the test that catches a broken migration, a bad {@code application.yml}, or an
 * entity that has drifted from the schema — because {@code ddl-auto} is {@code validate}, any
 * mismatch between a JPA entity and the Flyway-built schema fails right here rather than in
 * production.
 */
class ApnatutorApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
