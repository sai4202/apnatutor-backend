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
import com.apnatutor.lead.LeadPricing;
import com.apnatutor.requirement.domain.Requirement;
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

	/** SOURCE_OF_TRUTH.md §3.4. */
	private static final Duration LIFETIME = Duration.ofDays(30);

	private final RequirementRepository requirements;
	private final SubjectRepository subjects;
	private final LocationRepository locations;
	private final GradeLevelRepository gradeLevels;
	private final BoardRepository boards;
	private final Clock clock;

	public RequirementService(
			RequirementRepository requirements,
			SubjectRepository subjects,
			LocationRepository locations,
			GradeLevelRepository gradeLevels,
			BoardRepository boards,
			Clock clock) {
		this.requirements = requirements;
		this.subjects = subjects;
		this.locations = locations;
		this.gradeLevels = gradeLevels;
		this.boards = boards;
		this.clock = clock;
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
		// 5 credits must be charged 5, whatever the bands say by the time they tap.
		int cost = LeadPricing.creditsFor(request.budgetAmountPaise(), request.mode());

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
				clock.instant().plus(LIFETIME)));

		log.info("Requirement posted: id={} student={} subject={} cost={} credits",
				requirement.getId(), studentId, subject.getSlug(), cost);

		return requirement;
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
		return LeadPricing.creditsFor(budgetPaise, mode);
	}

	Instant now() {
		return clock.instant();
	}
}
