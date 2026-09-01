package com.apnatutor.requirement.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A student's posted tuition need.
 *
 * <p>Free to post. Tutors pay credits to see who posted it, which is the whole business model — so
 * the contact details are never on this entity's public projection.
 */
@Entity
@Table(name = "requirements")
public class Requirement {

	/**
	 * Fallback when no cap is configured. The live value is admin-configurable
	 * ({@code lead.unlock_cap}) and is <strong>locked onto each requirement at creation</strong>.
	 *
	 * <p>Locked, not read live, for the same reason the price is: a parent posting today is
	 * promised at most this many calls. Raising the setting later must not silently reopen their
	 * enquiry and send two more tutors after them.
	 */
	public static final int DEFAULT_UNLOCK_CAP = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "student_id", nullable = false)
	private Long studentId;

	@Column(name = "subject_id", nullable = false)
	private Long subjectId;

	@Column(name = "grade_level_id")
	private Long gradeLevelId;

	@Column(name = "board_id")
	private Long boardId;

	@Column(name = "location_id")
	private Long locationId;

	@Column(nullable = false, length = 16)
	private String mode;

	@Column(name = "budget_amount_paise")
	private Long budgetAmountPaise;

	@Column(name = "budget_unit", length = 16)
	private String budgetUnit;

	@Column(length = 60)
	private String frequency;

	@Column(name = "preferred_timing", length = 200)
	private String preferredTiming;

	@Column(name = "gender_preference", length = 16)
	private String genderPreference;

	@Column(columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RequirementStatus status = RequirementStatus.OPEN;

	/**
	 * Locked at creation and never recomputed (SOURCE_OF_TRUTH.md §3.1).
	 *
	 * <p>No setter, deliberately. A tutor looking at a lead priced at 5 credits must be charged 5,
	 * even if the pricing bands change between their reading the feed and tapping unlock.
	 */
	@Column(name = "unlock_cost_credits", nullable = false, updatable = false)
	private int unlockCostCredits;

	@Column(name = "unlock_count", nullable = false)
	private int unlockCount;

	/** Locked at creation alongside the price. Not updatable, for the same reason. */
	@Column(name = "unlock_cap", nullable = false, updatable = false)
	private int unlockCap = DEFAULT_UNLOCK_CAP;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected Requirement() {
		// Required by JPA.
	}

	public static Requirement post(
			Long studentId,
			Long subjectId,
			Long gradeLevelId,
			Long boardId,
			Long locationId,
			String mode,
			Long budgetAmountPaise,
			String budgetUnit,
			String frequency,
			String preferredTiming,
			String genderPreference,
			String description,
			int unlockCostCredits,
			int unlockCap,
			Instant expiresAt) {

		Requirement requirement = new Requirement();
		requirement.unlockCap = unlockCap;
		requirement.studentId = studentId;
		requirement.subjectId = subjectId;
		requirement.gradeLevelId = gradeLevelId;
		requirement.boardId = boardId;
		requirement.locationId = locationId;
		requirement.mode = mode;
		requirement.budgetAmountPaise = budgetAmountPaise;
		requirement.budgetUnit = budgetUnit;
		requirement.frequency = frequency;
		requirement.preferredTiming = preferredTiming;
		requirement.genderPreference = genderPreference;
		requirement.description = description;
		requirement.unlockCostCredits = unlockCostCredits;
		requirement.expiresAt = expiresAt;
		requirement.status = RequirementStatus.OPEN;
		return requirement;
	}

	/** Whether a tutor may still unlock this. */
	public boolean isUnlockable(Instant now) {
		return status == RequirementStatus.OPEN
				&& unlockCount < unlockCap
				&& now.isBefore(expiresAt);
	}

	/**
	 * Records an unlock and caps the requirement if that was the last slot.
	 *
	 * <p>Called with the row locked, so the count cannot be stale.
	 */
	public void recordUnlock() {
		if (unlockCount >= unlockCap) {
			throw new IllegalStateException("This requirement already has the maximum responses");
		}
		unlockCount++;
		if (unlockCount >= unlockCap) {
			// Removes it from every tutor's feed immediately, rather than letting five more
			// tutors open a lead they cannot buy.
			status = RequirementStatus.CAPPED;
		}
	}

	/** Frees a slot after a refund, reopening the requirement if it had capped out. */
	public void releaseUnlock() {
		if (unlockCount > 0) {
			unlockCount--;
		}
		if (status == RequirementStatus.CAPPED && unlockCount < unlockCap) {
			status = RequirementStatus.OPEN;
		}
	}

	/**
	 * Marks the student as having hired someone.
	 *
	 * <p>Terminal states are not reopened: a closed requirement that quietly returns to the feed
	 * would send tutors after a student who has already found someone.
	 */
	public void markHired() {
		requireActive();
		this.status = RequirementStatus.HIRED;
	}

	public void close() {
		requireActive();
		this.status = RequirementStatus.CLOSED;
	}

	public void expire() {
		if (status == RequirementStatus.OPEN || status == RequirementStatus.CAPPED) {
			this.status = RequirementStatus.EXPIRED;
		}
	}

	private void requireActive() {
		if (status != RequirementStatus.OPEN && status != RequirementStatus.CAPPED) {
			throw new IllegalStateException(
					"This requirement is already " + status.name().toLowerCase());
		}
	}

	public Long getId() {
		return id;
	}

	public Long getStudentId() {
		return studentId;
	}

	public Long getSubjectId() {
		return subjectId;
	}

	public Long getGradeLevelId() {
		return gradeLevelId;
	}

	public Long getBoardId() {
		return boardId;
	}

	public Long getLocationId() {
		return locationId;
	}

	public String getMode() {
		return mode;
	}

	public Long getBudgetAmountPaise() {
		return budgetAmountPaise;
	}

	public String getBudgetUnit() {
		return budgetUnit;
	}

	public String getFrequency() {
		return frequency;
	}

	public String getPreferredTiming() {
		return preferredTiming;
	}

	public String getGenderPreference() {
		return genderPreference;
	}

	public String getDescription() {
		return description;
	}

	public RequirementStatus getStatus() {
		return status;
	}

	public int getUnlockCostCredits() {
		return unlockCostCredits;
	}

	public int getUnlockCount() {
		return unlockCount;
	}

	public int remainingSlots() {
		return Math.max(unlockCap - unlockCount, 0);
	}

	/** The cap this requirement was posted under, not the current setting. */
	public int getUnlockCap() {
		return unlockCap;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
