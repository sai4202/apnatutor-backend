package com.apnatutor.requirement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.apnatutor.catalog.BoardRepository;
import com.apnatutor.catalog.GradeLevelRepository;
import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.lead.LeadPricingService;
import com.apnatutor.notification.NotificationService;
import com.apnatutor.notification.domain.NotificationType;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.settings.SettingsService;
import com.apnatutor.requirement.dto.RequirementDtos.PostRequirementRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting and managing requirements.
 *
 * <p>Free for students, always. The platform earns when a tutor unlocks one.
 */
@Service
public class RequirementService {

	private static final Logger log = LoggerFactory.getLogger(RequirementService.class);

	/** Fallback if the setting is missing. SOURCE_OF_TRUTH.md §3.4. */
	private static final int DEFAULT_LIFETIME_DAYS = 30;

	/**
	 * How many tutors are told about a new enquiry, as a multiple of its unlock cap.
	 *
	 * <p>Four candidates per slot. Enough that the slots fill quickly even when most recipients are
	 * busy, few enough that a tutor who opens the notification has a real chance of still getting it.
	 */
	private static final int NOTIFY_FAN_OUT_MULTIPLE = 4;

	private final RequirementRepository requirements;
	private final SubjectRepository subjects;
	private final LocationRepository locations;
	private final GradeLevelRepository gradeLevels;
	private final BoardRepository boards;
	private final LeadPricingService pricing;
	private final SettingsService settings;
	private final NotificationService notifications;
	private final Clock clock;

	public RequirementService(
			RequirementRepository requirements,
			SubjectRepository subjects,
			LocationRepository locations,
			GradeLevelRepository gradeLevels,
			BoardRepository boards,
			LeadPricingService pricing,
			SettingsService settings,
			NotificationService notifications,
			Clock clock) {
		this.requirements = requirements;
		this.subjects = subjects;
		this.locations = locations;
		this.gradeLevels = gradeLevels;
		this.boards = boards;
		this.pricing = pricing;
		this.settings = settings;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** Admin-configurable, read at posting time so a change applies to new enquiries. */
	private Duration lifetime() {
		return settings.durationDays(
				SettingsService.REQUIREMENT_LIFETIME_DAYS, DEFAULT_LIFETIME_DAYS);
	}

	@Transactional
	public Requirement post(Long studentId, PostRequirementRequest request) {
		Subject subject = subjects.findById(request.subjectId())
				.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
						"Unknown subject: " + request.subjectId()));

		// Only leaf subjects. "School Tuition" is a browsing category — a requirement posted
		// against it would match every school tutor on the platform and help nobody.
		if (!subject.isLeaf()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"'%s' is a category. Choose a specific subject within it."
							.formatted(subject.getName()));
		}

		validateCatalogReferences(request);

		// PRICED ONCE, HERE (SoT §3.1). Never recomputed at unlock time: a tutor shown a lead at
		// 5 credits must be charged 5, whatever an admin repriced the bands to in between.
		int cost = pricing.creditsFor(request.budgetAmountPaise(), request.mode());

		// The cap is locked here for the same reason. A parent posting today is promised at most
		// this many calls; raising the setting later must not reopen their enquiry.
		int cap = pricing.currentUnlockCap();

		Requirement requirement = requirements.save(Requirement.post(
				studentId,
				request.subjectId(),
				request.gradeLevelId(),
				request.boardId(),
				request.locationId(),
				request.mode(),
				request.budgetAmountPaise(),
				request.budgetUnit(),
				request.frequency(),
				request.preferredTiming(),
				request.genderPreference(),
				request.description(),
				cost,
				cap,
				clock.instant().plus(lifetime())));

		notifyMatchingTutors(requirement, subject);

		log.info("Requirement posted: id={} student={} subject={} cost={} credits",
				requirement.getId(), studentId, subject.getSlug(), cost);

		return requirement;
	}

	/**
	 * Tells matching tutors a new enquiry is live.
	 *
	 * <p>Without this the feed is a page tutors have to remember to visit, and a lead that sits
	 * unseen for a day is usually a lead the parent has already solved elsewhere. Speed of first
	 * response is what the parent is buying and what the tutor is paying for.
	 *
	 * <h2>Why only a few tutors are told</h2>
	 *
	 * <p>Capped at {@link #NOTIFY_FAN_OUT} rather than everyone who matches. A lead has only a
	 * handful of unlock slots, so messaging two hundred tutors would mean the great majority arrive
	 * to find it taken — which teaches them the notifications are not worth opening. The cap is a
	 * small multiple of the slots, and the ordering favours verified, well-rated tutors, which is
	 * also what the parent wants answering.
	 *
	 * <h2>Why this is not wrapped in a try/catch</h2>
	 *
	 * <p>An obvious instinct is to swallow failures here so a notification bug cannot cost a parent
	 * their enquiry. It would not work: {@code notify()} joins this transaction, so a failed write
	 * marks it rollback-only and the commit throws regardless of what is caught. A catch would only
	 * hide where the failure came from. Isolating the writes in {@code REQUIRES_NEW} would make the
	 * catch real, but would also let notifications survive pointing at a requirement that never
	 * committed. In practice every failure mode here is database-level, which would fail the
	 * requirement's own save anyway — so sharing the transaction loses nothing and stays honest.
	 *
	 * <p>Delivery is a separate matter and already safe: {@link NotificationService} sends after
	 * commit, so a dead SMS gateway cannot roll this back.
	 */
	private void notifyMatchingTutors(Requirement requirement, Subject subject) {
		// A small multiple of the enquiry's own cap, so a change to the cap carries through.
		int fanOut = requirement.getUnlockCap() * NOTIFY_FAN_OUT_MULTIPLE;

		List<Long> tutorIds = requirements.findTutorsToNotify(
				requirement.getSubjectId(),
				requirement.getLocationId(),
				requirement.getMode(),
				fanOut);

		for (Long tutorId : tutorIds) {
			notifications.notify(
					tutorId,
					NotificationType.NEW_MATCHING_LEAD,
					"New " + subject.getName() + " enquiry",
					"A student is looking for a %s tutor. %d credits to see their details."
							.formatted(subject.getName(), requirement.getUnlockCostCredits()),
					"REQUIREMENT",
					requirement.getId());
		}

		log.info("Notified {} tutor(s) about requirement {}", tutorIds.size(), requirement.getId());
	}

	@Transactional(readOnly = true)
	public List<Requirement> forStudent(Long studentId) {
		return requirements.findByStudentIdOrderByCreatedAtDesc(studentId);
	}

	/**
	 * Loads a requirement the caller owns.
	 *
	 * <p>Ownership is checked here rather than in the controller, so every path that reaches a
	 * requirement by id goes through the same check. A student must never read another's enquiry —
	 * it contains their child's details and their budget.
	 */
	@Transactional(readOnly = true)
	public Requirement ownedBy(Long requirementId, Long studentId) {
		Requirement requirement = requirements.findById(requirementId)
				.orElseThrow(() -> ApiException.notFound("Requirement"));

		if (!requirement.getStudentId().equals(studentId)) {
			// NOT_FOUND rather than FORBIDDEN. A 403 would confirm the requirement exists, which
			// tells an attacker enumerating ids more than they should learn.
			throw ApiException.notFound("Requirement");
		}

		return requirement;
	}

	@Transactional
	public Requirement markHired(Long requirementId, Long studentId) {
		Requirement requirement = ownedBy(requirementId, studentId);
		try {
			requirement.markHired();
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.REQUIREMENT_NOT_OPEN, e.getMessage());
		}
		return requirements.save(requirement);
	}

	@Transactional
	public Requirement close(Long requirementId, Long studentId) {
		Requirement requirement = ownedBy(requirementId, studentId);
		try {
			requirement.close();
		} catch (IllegalStateException e) {
			throw new ApiException(ErrorCode.REQUIREMENT_NOT_OPEN, e.getMessage());
		}
		return requirements.save(requirement);
	}

	/**
	 * Expires requirements past their 30 days.
	 *
	 * <p>A single bulk UPDATE rather than loading and saving each one. This runs against every open
	 * requirement on the platform, and the alternative is thousands of round trips for a job that
	 * changes one column.
	 */
	@Transactional
	public int expireOverdue() {
		int expired = requirements.expireOlderThan(clock.instant());
		if (expired > 0) {
			log.info("Expired {} requirement(s) past their 30-day lifetime", expired);
		}
		return expired;
	}

	private void validateCatalogReferences(PostRequirementRequest request) {
		if (request.locationId() != null) {
			locations.findById(request.locationId()).orElseThrow(
					() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown location: " + request.locationId()));
		}
		if (request.gradeLevelId() != null) {
			gradeLevels.findById(request.gradeLevelId()).orElseThrow(
					() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown grade level: " + request.gradeLevelId()));
		}
		if (request.boardId() != null) {
			boards.findById(request.boardId()).orElseThrow(
					() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown board: " + request.boardId()));
		}
	}

	/** What a lead would cost, so the post form can show it before submitting. */
	public int quotePrice(Long budgetPaise, String mode) {
		return pricing.creditsFor(budgetPaise, mode);
	}

	Instant now() {
		return clock.instant();
	}
}
