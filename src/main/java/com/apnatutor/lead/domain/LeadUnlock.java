package com.apnatutor.lead.domain;

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
 * A tutor's paid engagement with a student's requirement.
 *
 * <p><strong>Deliberately generic</strong> (SOURCE_OF_TRUTH.md Invariant 2). Named for the unlock it
 * represents today, but carrying {@code engagementType} so a v2 booking becomes another type on the
 * same connection graph. Reviews, notifications and the "my students" / "my tutors" lists all key
 * off this table; if bookings arrived as a parallel table, every one of those would need a second
 * code path.
 */
@Entity
@Table(name = "lead_unlocks")
public class LeadUnlock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "requirement_id", nullable = false)
	private Long requirementId;

	/** The tutor's user id — billing, verification and unlocks all key off the account. */
	@Column(name = "tutor_id", nullable = false)
	private Long tutorId;

	@Enumerated(EnumType.STRING)
	@Column(name = "engagement_type", nullable = false, length = 16)
	private EngagementType engagementType = EngagementType.UNLOCK;

	/** What was actually charged, not what the requirement says now. */
	@Column(name = "credits_spent", nullable = false)
	private int creditsSpent;

	@Column(name = "intro_message", length = 1000)
	private String introMessage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private UnlockStatus status = UnlockStatus.ACTIVE;

	@Column(name = "unlocked_at", nullable = false)
	private Instant unlockedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected LeadUnlock() {
		// Required by JPA.
	}

	public static LeadUnlock record(
			Long requirementId, Long tutorId, int creditsSpent, String introMessage, Instant at) {

		LeadUnlock unlock = new LeadUnlock();
		unlock.requirementId = requirementId;
		unlock.tutorId = tutorId;
		unlock.creditsSpent = creditsSpent;
		unlock.introMessage = introMessage;
		unlock.unlockedAt = at;
		unlock.engagementType = EngagementType.UNLOCK;
		unlock.status = UnlockStatus.ACTIVE;
		return unlock;
	}

	/**
	 * Marks this refunded after an approved dispute.
	 *
	 * <p>The row is kept rather than deleted — a refund is part of the history, and deleting it
	 * would leave the ledger entry pointing at nothing.
	 */
	public void markRefunded() {
		this.status = UnlockStatus.REFUNDED;
	}

	public boolean isActive() {
		return status == UnlockStatus.ACTIVE;
	}

	public Long getId() {
		return id;
	}

	public Long getRequirementId() {
		return requirementId;
	}

	public Long getTutorId() {
		return tutorId;
	}

	public EngagementType getEngagementType() {
		return engagementType;
	}

	public int getCreditsSpent() {
		return creditsSpent;
	}

	public String getIntroMessage() {
		return introMessage;
	}

	public UnlockStatus getStatus() {
		return status;
	}

	public Instant getUnlockedAt() {
		return unlockedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
