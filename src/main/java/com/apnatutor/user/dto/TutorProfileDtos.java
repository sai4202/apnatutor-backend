package com.apnatutor.user.dto;

import java.time.LocalDate;
import java.util.List;

import com.apnatutor.user.domain.FeeUnit;
import com.apnatutor.user.domain.Gender;
import com.apnatutor.user.domain.TutorProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for tutor profiles.
 *
 * <p>Two separate response types, deliberately:
 *
 * <ul>
 *   <li>{@link OwnerView} — everything, returned only to the tutor who owns it.
 *   <li>{@link PublicView} — what a parent may see. <strong>No contact details, no document URLs,
 *       no date of birth.</strong>
 * </ul>
 *
 * <p>Separate types rather than one type with conditional fields, because a conditional is
 * something a future edit can get wrong silently. If a field is absent from {@code PublicView},
 * there is no code path that can leak it.
 */
public final class TutorProfileDtos {

	private TutorProfileDtos() {
	}

	// --- Requests -----------------------------------------------------------------------------

	/*
	 * Numbers and booleans in request records are BOXED, deliberately.
	 *
	 * Spring Boot 4 ships Jackson 3, which flipped FAIL_ON_NULL_FOR_PRIMITIVES to true. Under
	 * Jackson 2 an omitted `boolean` quietly became false; now it is a hard parse error, and the
	 * caller gets MALFORMED_REQUEST for leaving out a field they never set.
	 *
	 * Boxing keeps "absent" representable and lets the service decide the default explicitly,
	 * rather than turning the global Jackson default off for every endpoint in the application to
	 * accommodate a handful of optional fields.
	 */

	@Schema(description = "Core profile details")
	public record UpdateBasicsRequest(
			@Size(max = 120) String displayName,
			@Size(max = 160) String headline,
			@Size(max = 5000) String bio,
			Gender gender,
			LocalDate dateOfBirth,
			@Min(0) @Max(70) Integer experienceYears,
			List<@Size(max = 60) String> languages,
			Boolean offersDemo,
			@Size(max = 400) String availabilityNote) {

		public int experienceYearsOrZero() {
			return experienceYears == null ? 0 : experienceYears;
		}

		public boolean offersDemoOrFalse() {
			return Boolean.TRUE.equals(offersDemo);
		}
	}

	@Schema(description = "Fee range. Amounts are in paise — ₹4,500 is 450000.")
	public record UpdateFeesRequest(
			@PositiveOrZero Long feeMinPaise,
			@PositiveOrZero Long feeMaxPaise,
			FeeUnit feeUnit,
			Boolean feeNegotiable) {

		/**
		 * Checked here rather than only by the database constraint, so the caller gets a field-level
		 * validation message instead of a 500 from a constraint violation.
		 */
		public UpdateFeesRequest {
			if (feeMinPaise != null && feeMaxPaise != null && feeMaxPaise < feeMinPaise) {
				throw new IllegalArgumentException(
						"Maximum fee cannot be less than the minimum fee");
			}
		}

		public boolean negotiableOrFalse() {
			return Boolean.TRUE.equals(feeNegotiable);
		}
	}

	@Schema(description = "Where and how classes happen")
	public record UpdateTeachingRequest(
			@NotEmpty(message = "Choose at least one way you teach")
			List<String> teachingModes,
			@Min(0) @Max(100) Integer travelRadiusKm,
			List<Long> locationIds) {

		public int travelRadiusOrZero() {
			return travelRadiusKm == null ? 0 : travelRadiusKm;
		}
	}

	@Schema(description = "One subject this tutor teaches")
	public record TutorSubjectRequest(
			@NotNull(message = "Choose a subject") Long subjectId,
			@PositiveOrZero Long feePaise,
			FeeUnit feeUnit,
			List<Long> gradeLevelIds,
			List<Long> boardIds) {
	}

	@Schema(description = "Replaces the tutor's whole subject list")
	public record UpdateSubjectsRequest(
			@NotEmpty(message = "Add at least one subject you teach")
			List<@Valid TutorSubjectRequest> subjects) {
	}

	@Schema(description = "A degree or certification")
	public record QualificationRequest(
			@NotNull @Size(min = 2, max = 160) String degree,
			@NotNull @Size(min = 2, max = 200) String institution,
			@Min(1950) @Max(2100) Integer year) {
	}

	// --- Responses ----------------------------------------------------------------------------

	@Schema(description = "A subject on a tutor profile")
	public record SubjectView(
			Long subjectId,
			String name,
			String slug,
			Long feePaise,
			FeeUnit feeUnit,
			List<String> gradeLevels,
			List<String> boards) {
	}

	@Schema(description = "A locality a tutor travels to")
	public record LocationView(Long locationId, String displayName, String slug) {
	}

	@Schema(description = "A qualification, as shown publicly — no document URL")
	public record QualificationView(
			Long id, String degree, String institution, Integer year, boolean verified) {
	}

	/** The tutor's own view of their profile. */
	@Schema(description = "Full profile, for its owner")
	public record OwnerView(
			Long id,
			String displayName,
			String headline,
			String bio,
			String photoUrl,
			Gender gender,
			LocalDate dateOfBirth,
			int experienceYears,
			Long feeMinPaise,
			Long feeMaxPaise,
			FeeUnit feeUnit,
			boolean feeNegotiable,
			List<String> teachingModes,
			int travelRadiusKm,
			List<String> languages,
			boolean offersDemo,
			String availabilityNote,
			List<SubjectView> subjects,
			List<LocationView> locations,
			List<QualificationView> qualifications,
			@Schema(description = "0-100. Must reach 60 before the profile can be published")
			int profileCompleteness,
			boolean published,
			@Schema(description = "What still needs filling in before this can be published")
			List<String> missingForPublish) {
	}

	/**
	 * What a parent sees.
	 *
	 * <p>Note what is <em>not</em> here: no phone, no email, no date of birth, no document URLs.
	 * Contact details are what tutors pay credits to unlock, so a public profile leaking one would
	 * remove the business model rather than merely degrade it.
	 */
	@Schema(description = "Public profile — contains no contact details by construction")
	public record PublicView(
			Long id,
			String displayName,
			String headline,
			String bio,
			String photoUrl,
			Gender gender,
			int experienceYears,
			Long feeMinPaise,
			Long feeMaxPaise,
			FeeUnit feeUnit,
			boolean feeNegotiable,
			List<String> teachingModes,
			List<String> languages,
			boolean offersDemo,
			String availabilityNote,
			List<SubjectView> subjects,
			List<LocationView> locations,
			List<QualificationView> qualifications,
			java.math.BigDecimal avgRating,
			int reviewCount) {
	}

	/** Fields shared by both views, so the two cannot drift apart on the common parts. */
	static List<String> modes(TutorProfile profile) {
		return List.of(profile.getTeachingModes());
	}
}
