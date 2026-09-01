package com.apnatutor.review;

import java.util.List;

import com.apnatutor.review.dto.ReviewDtos.PublicView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reviews shown on a tutor's public profile.
 *
 * <p>Unauthenticated, and sits under {@code /public/} alongside the profile it belongs to, taking
 * the same {@code tutor_profiles.id}. Approved reviews only — the filter is in the SQL, so there is
 * no code path here that could return a pending one.
 */
@RestController
@RequestMapping("/api/v1/public/tutors")
@Tag(name = "Reviews — public", description = "Published reviews on a tutor profile")
public class PublicReviewController {

	private final ReviewService service;

	public PublicReviewController(ReviewService service) {
		this.service = service;
	}

	@GetMapping("/{profileId}/reviews")
	@Operation(
			summary = "Published reviews for a tutor",
			description = "Newest first. Reviewers appear as \"Priya S.\" — never a full name. A "
					+ "tutor reply is included only once it has been approved as well.")
	public ResponseEntity<List<PublicView>> forTutor(@PathVariable Long profileId) {
		return ResponseEntity.ok(service.publishedForProfile(profileId).stream()
				.map(PublicView::from)
				.toList());
	}
}
