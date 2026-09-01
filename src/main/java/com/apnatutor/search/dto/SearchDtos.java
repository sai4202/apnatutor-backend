package com.apnatutor.search.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Search request and result shapes.
 *
 * <p>{@link TutorSearchResult} has <strong>no contact fields of any kind</strong> — no phone, no
 * email, no exact address, no date of birth. Not masked ones: absent ones. A field that does not
 * exist on the type cannot be leaked by a future edit, whereas a masked field is one careless
 * change away from being unmasked.
 */
public final class SearchDtos {

	private SearchDtos() {
	}

	/** Free-text sort options, kept as an enum so an arbitrary string cannot reach the SQL. */
	public enum SortOrder {
		/** Rating, then review count. The default: a 5.0 from 40 people beats a 5.0 from one. */
		RELEVANCE,
		RATING,
		FEE_LOW_TO_HIGH,
		FEE_HIGH_TO_LOW,
		EXPERIENCE,
		RECENTLY_ACTIVE
	}

	/**
	 * Everything a search can filter on.
	 *
	 * <p>All optional. A bare request returns every published tutor, newest-rated first, which is
	 * what a visitor landing on /tutors with no query should see.
	 */
	public record TutorSearchQuery(
			@Schema(description = "Free text — matches tutor name and subject name") String q,
			@Schema(description = "Subject slug, e.g. mathematics") String subject,
			@Schema(description = "City or locality slug, e.g. hyderabad") String location,
			@Schema(description = "Board slug, e.g. cbse") String board,
			@Schema(description = "Grade slug, e.g. class-10") String grade,
			@Schema(description = "STUDENT_HOME, TUTOR_PLACE or ONLINE") String mode,
			@Schema(description = "Minimum fee in paise") Long feeMinPaise,
			@Schema(description = "Maximum fee in paise") Long feeMaxPaise,
			@Schema(description = "MALE, FEMALE or OTHER") String gender,
			Integer minExperienceYears,
			@Schema(description = "1-5") BigDecimal minRating,
			@Schema(description = "Only tutors whose ID has been verified") Boolean verifiedOnly,
			SortOrder sort) {
	}

	/** One tutor in a result list. */
	@Schema(description = "A search result. Contains no contact details by construction.")
	public record TutorSearchResult(
			Long id,
			String displayName,
			String headline,
			String photoUrl,
			int experienceYears,
			Long feeMinPaise,
			Long feeMaxPaise,
			String feeUnit,
			boolean feeNegotiable,
			List<String> teachingModes,
			List<String> subjects,
			@Schema(description = "Nearest listed locality, e.g. \"Gachibowli, Hyderabad\"")
			String locality,
			BigDecimal avgRating,
			int reviewCount,
			@Schema(description = "True when an admin has approved a government ID")
			boolean idVerified) {
	}

	/** Counts alongside a result page, so the UI can show what each filter would return. */
	@Schema(description = "How many tutors match, broken down for the filter sidebar")
	public record SearchFacets(
			long total,
			long onlineAvailable,
			long verifiedOnly) {
	}
}
