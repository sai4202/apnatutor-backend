package com.apnatutor.requirement.dto;

import java.time.Instant;
import java.util.List;

import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.domain.RequirementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Requirement request and response shapes.
 *
 * <p>Three response types, and the differences between them are the business model:
 *
 * <ul>
 *   <li>{@link StudentView} — the student's own enquiry, in full.
 *   <li>{@link LeadPreview} — what a tutor sees <strong>before paying</strong>. No name, no phone,
 *       no exact address. Enough to judge whether the lead is worth buying, not enough to act on
 *       without buying it.
 *   <li>{@link UnlockedLead} — what a tutor sees after paying. Contact details included.
 * </ul>
 *
 * <p>Separate types rather than one with conditional fields. A conditional is something a future
 * edit can get wrong silently; a field absent from {@code LeadPreview} cannot be leaked into it.
 */
public final class RequirementDtos {

	private RequirementDtos() {
	}

	// --- Requests -----------------------------------------------------------------------------

	@Schema(description = "Post a tuition requirement. Free.")
	public record PostRequirementRequest(
			@NotNull(message = "Choose a subject") Long subjectId,
			Long gradeLevelId,
			Long boardId,
			Long locationId,

			@Schema(description = "STUDENT_HOME, TUTOR_PLACE or ONLINE")
			@NotBlank(message = "Choose where classes should happen")
			String mode,

			@Schema(description = "Budget in paise. ₹3,000/month is 300000.")
			@PositiveOrZero Long budgetAmountPaise,
			@Schema(description = "PER_HOUR or PER_MONTH") String budgetUnit,

			@Size(max = 60) String frequency,
			@Size(max = 200) String preferredTiming,
			@Schema(description = "MALE, FEMALE or ANY") String genderPreference,
			@Size(max = 4000) String description) {
	}

	@Schema(description = "A tutor's introduction, sent with an unlock")
	public record UnlockRequest(
			@Schema(example = "Hello, I teach Class 10 Maths in Gachibowli and have a slot free on weekday evenings.")
			@Size(max = 1000) String introMessage) {
	}

	// --- Responses ----------------------------------------------------------------------------

	@Schema(description = "A tutor who responded, as the student sees them")
	public record RespondingTutor(
			Long tutorProfileId,
			String displayName,
			String headline,
			@Schema(description = "Revealed to the student — this tutor paid to reach them")
			String phone,
			String introMessage,
			Instant respondedAt) {
	}

	/** The student's own enquiry. */
	@Schema(description = "A requirement, as its owner sees it")
	public record StudentView(
			Long id,
			String subject,
			String gradeLevel,
			String board,
			String location,
			String mode,
			Long budgetAmountPaise,
			String budgetUnit,
			String frequency,
			String preferredTiming,
			String genderPreference,
			String description,
			RequirementStatus status,
			@Schema(description = "How many tutors have responded, of a maximum of 5")
			int responseCount,
			int remainingSlots,
			Instant expiresAt,
			Instant postedAt,
			List<RespondingTutor> respondingTutors) {
	}

	/**
	 * What a tutor sees before paying.
	 *
	 * <p>Note what is absent: no student name, no phone, no exact address. This is the masked view
	 * that makes an unlock worth paying for — enough to judge the lead, not enough to act on it.
	 */
	@Schema(description = "A lead preview. Deliberately carries no contact details.")
	public record LeadPreview(
			Long id,
			String subject,
			String gradeLevel,
			String board,
			@Schema(description = "Area only, never a street address")
			String area,
			String mode,
			Long budgetAmountPaise,
			String budgetUnit,
			String frequency,
			String preferredTiming,
			String genderPreference,
			String description,
			@Schema(description = "Credits this costs to unlock. Locked when the enquiry was posted.")
			int unlockCostCredits,
			@Schema(description = "How many of the five response slots are left")
			int remainingSlots,
			Instant postedAt) {

		public static LeadPreview from(
				Requirement requirement, String subject, String gradeLevel, String board, String area) {
			return new LeadPreview(
					requirement.getId(),
					subject,
					gradeLevel,
					board,
					area,
					requirement.getMode(),
					requirement.getBudgetAmountPaise(),
					requirement.getBudgetUnit(),
					requirement.getFrequency(),
					requirement.getPreferredTiming(),
					requirement.getGenderPreference(),
					requirement.getDescription(),
					requirement.getUnlockCostCredits(),
					requirement.remainingSlots(),
					requirement.getCreatedAt());
		}
	}

	/** What a tutor sees after paying. */
	@Schema(description = "An unlocked lead, with the contact details the tutor paid for")
	public record UnlockedLead(
			Long id,
			@Schema(description = "The unlock itself, not the enquiry. Needed to raise a dispute.")
			Long unlockId,
			String subject,
			String area,
			String mode,
			Long budgetAmountPaise,
			String budgetUnit,
			String description,
			@Schema(description = "Revealed — this is what the credits bought")
			String studentName,
			String studentPhone,
			int creditsSpent,
			Instant unlockedAt,
			@Schema(description = "Whether this lead has already been disputed")
			boolean disputed) {
	}

	@Schema(description = "What a lead would cost, quoted before posting")
	public record PriceQuote(int credits, String explanation) {
	}
}
