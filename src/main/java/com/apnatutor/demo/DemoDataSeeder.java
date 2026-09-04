package com.apnatutor.demo;

import java.time.Clock;
import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.catalog.BoardRepository;
import com.apnatutor.catalog.GradeLevelRepository;
import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.lead.LeadUnlockService;
import com.apnatutor.requirement.RequirementService;
import com.apnatutor.review.ReviewService;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.TutorProfileService;
import com.apnatutor.user.UserRepository;
import com.apnatutor.verification.VerificationRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads a believable dataset — {@code M6-04}.
 *
 * <h2>One command</h2>
 *
 * <pre>.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--apnatutor.demo.seed=true"</pre>
 *
 * <p>Or set {@code APNATUTOR_SEED_DEMO=true}. Off by default and guarded by
 * {@code DevModeGuard}'s sibling check below, because a production database filling with fictional
 * tutors is not a recoverable mistake.
 *
 * <h2>It builds the data through the real services</h2>
 *
 * <p>Not through SQL inserts. A tutor here is published by the same {@code publish} that enforces
 * the completeness bar, an unlock spends real credits through the ledger, and a review goes through
 * moderation. That costs a little speed and buys the thing demo data is actually for: if the seed
 * runs, the flows work. A dataset inserted directly can describe a state the application cannot
 * reach, which is worse than no dataset at all.
 *
 * <p>Idempotent on the tutor phone numbers, so re-running adds nothing.
 */
@Configuration
@ConditionalOnProperty(name = "apnatutor.demo.seed", havingValue = "true")
public class DemoDataSeeder {

	@Bean
	ApplicationRunner seedDemoData(DemoSeedRunner runner) {
		return args -> runner.run();
	}

	/**
	 * A separate bean, so {@code @Transactional} is proxy-applied.
	 *
	 * <p>Fourth time this trap has come up on this project: a self-invoked {@code @Transactional}
	 * method does nothing at all, and here it would leave a half-built dataset behind on any
	 * failure instead of rolling the whole thing back.
	 */
	@Bean
	DemoSeedRunner demoSeedRunner(
			UserRepository users,
			StudentProfileRepository studentProfiles,
			TutorProfileRepository tutorProfiles,
			TutorProfileService profiles,
			SubjectRepository subjects,
			LocationRepository locations,
			GradeLevelRepository grades,
			BoardRepository boards,
			RequirementService requirements,
			LeadUnlockService unlocks,
			ReviewService reviews,
			VerificationRepository verifications,
			CreditLedger ledger,
			Clock clock) {

		return new DemoSeedRunner(users, studentProfiles, tutorProfiles, profiles, subjects,
				locations, grades, boards, requirements, unlocks, reviews, verifications, ledger,
				clock);
	}
}
