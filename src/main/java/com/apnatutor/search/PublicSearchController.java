package com.apnatutor.search;

import java.math.BigDecimal;
import java.time.Duration;

import com.apnatutor.common.web.PageResponse;
import com.apnatutor.search.dto.SearchDtos.SearchFacets;
import com.apnatutor.search.dto.SearchDtos.SortOrder;
import com.apnatutor.search.dto.SearchDtos.TutorSearchQuery;
import com.apnatutor.search.dto.SearchDtos.TutorSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public tutor search.
 *
 * <p>Unauthenticated by design — this is how a parent arriving from Google finds anything, and
 * requiring a sign-in first would lose most of them.
 *
 * <p>The response type carries no contact fields at all. Not masked ones: absent ones. A field that
 * does not exist on the type cannot be leaked by a future edit.
 */
@RestController
@RequestMapping("/api/v1/public/tutors")
@Tag(name = "Search", description = "Finding tutors")
public class PublicSearchController {

	/**
	 * Short, not long. Results change as tutors publish and edit, and a stale result page is worse
	 * than a slightly slower one — a parent who contacts a tutor who has since gone offline blames
	 * us, not the cache.
	 */
	private static final Duration CACHE = Duration.ofMinutes(5);

	private final TutorSearchService searchService;

	public PublicSearchController(TutorSearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping
	@Operation(
			summary = "Search tutors",
			description = """
					All filters optional. A bare request returns every published tutor, \
					best-rated first.

					Online tutors match every location filter — they can teach a student in any \
					city, and excluding them would hide exactly the tutors most able to help.

					Fee bounds compare against a tutor's starting fee, so someone whose lowest \
					rate is inside your budget is not excluded because their highest is not.""")
	public ResponseEntity<PageResponse<TutorSearchResult>> search(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String subject,
			@RequestParam(required = false) String location,
			@RequestParam(required = false) String board,
			@RequestParam(required = false) String grade,
			@RequestParam(required = false) String mode,
			@RequestParam(required = false) Long feeMinPaise,
			@RequestParam(required = false) Long feeMaxPaise,
			@RequestParam(required = false) String gender,
			@RequestParam(required = false) Integer minExperienceYears,
			@RequestParam(required = false) BigDecimal minRating,
			@RequestParam(required = false) Boolean verifiedOnly,
			@RequestParam(required = false) SortOrder sort,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		TutorSearchQuery query = new TutorSearchQuery(
				q, subject, location, board, grade, mode,
				feeMinPaise, feeMaxPaise, gender, minExperienceYears, minRating, verifiedOnly, sort);

		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(CACHE).cachePublic())
				.body(searchService.search(query, page, size));
	}

	@GetMapping("/facets")
	@Operation(
			summary = "Counts for the filter sidebar",
			description = "How many results each filter would leave, so a visitor can see what "
					+ "narrowing costs them before they click.")
	public ResponseEntity<SearchFacets> facets(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String subject,
			@RequestParam(required = false) String location) {

		TutorSearchQuery query = new TutorSearchQuery(
				q, subject, location, null, null, null, null, null, null, null, null, null, null);

		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(CACHE).cachePublic())
				.body(searchService.facets(query));
	}
}
