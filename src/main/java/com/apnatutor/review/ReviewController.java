package com.apnatutor.review;

import java.util.List;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.review.dto.ReviewDtos.OwnerView;
import com.apnatutor.review.dto.ReviewDtos.SubmitRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student writing about a tutor who responded to one of their enquiries.
 *
 * <p>Every method works from {@code currentUser.userId()}, never from an id in the request, so one
 * student cannot write or edit a review as another — the same construction used for profiles and
 * verification.
 *
 * <p>Tutors are addressed by {@code tutor_profiles.id} throughout, the same id the public profile
 * pages and the student's "tutors who responded" list already use. One tutor identifier on the
 * wire, so the wrong one cannot quietly address a different person.
 */
@RestController
@RequestMapping("/api/v1/student/reviews")
@PreAuthorize("hasRole('STUDENT')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reviews", description = "Reviewing a tutor you were connected to")
public class ReviewController {

	private final ReviewService service;

	public ReviewController(ReviewService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "Reviews I have written",
			description = "With the status of each, so a student can see that something is "
					+ "awaiting moderation rather than assuming it was thrown away.")
	public ResponseEntity<List<OwnerView>> mine(CurrentUser currentUser) {
		return ResponseEntity.ok(service.writtenBy(currentUser.userId()).stream()
				.map(OwnerView::from)
				.toList());
	}

	@GetMapping("/eligibility/{tutorProfileId}")
	@Operation(
			summary = "May I review this tutor?",
			description = "True only if this tutor responded to one of my enquiries and I have not "
					+ "already reviewed them. Offering a review box and then refusing the "
					+ "submission is worse than not offering one.")
	public ResponseEntity<Boolean> eligibility(
			CurrentUser currentUser, @PathVariable Long tutorProfileId) {
		return ResponseEntity.ok(service.mayReview(currentUser.userId(), tutorProfileId));
	}

	@PostMapping("/tutor/{tutorProfileId}")
	@Operation(
			summary = "Review a tutor",
			description = "Goes to a moderator before it is published; nothing user-written "
					+ "appears on a public profile unreviewed. Submitting again while it is still "
					+ "pending edits what is there rather than being refused as a duplicate.")
	public ResponseEntity<OwnerView> submit(
			CurrentUser currentUser,
			@PathVariable Long tutorProfileId,
			@Valid @RequestBody SubmitRequest request) {

		return ResponseEntity.ok(OwnerView.from(service.write(
				currentUser.userId(), tutorProfileId,
				request.rating(), request.title(), request.body())));
	}

	@PutMapping("/{id}")
	@Operation(
			summary = "Edit a review that is still pending",
			description = "Only while it is awaiting moderation. Editing after approval would put "
					+ "unreviewed text on a public page under a moderator's approval.")
	public ResponseEntity<OwnerView> revise(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody SubmitRequest request) {

		return ResponseEntity.ok(OwnerView.from(service.revise(
				currentUser.userId(), id, request.rating(), request.title(), request.body())));
	}
}
