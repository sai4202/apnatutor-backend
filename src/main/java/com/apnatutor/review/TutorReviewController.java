package com.apnatutor.review;

import java.util.List;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.review.dto.ReviewDtos.ReplyRequest;
import com.apnatutor.review.dto.ReviewDtos.TutorView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tutor reading what has been written about them, and answering it once.
 *
 * <p>The reply is the tutor's only recourse against an unfair review, and it is moderated too: a
 * reply is public text about a named student, written by someone with a grievance, which is exactly
 * the material moderation exists for.
 */
@RestController
@RequestMapping("/api/v1/tutor/reviews")
@PreAuthorize("hasRole('TUTOR')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reviews — tutor", description = "Reviews of me, and my replies")
public class TutorReviewController {

	private final ReviewService service;

	public TutorReviewController(ReviewService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "Reviews of me",
			description = "Includes reviews still awaiting moderation — they are about you either "
					+ "way, and you will see them the moment they publish. Reviewer names are not "
					+ "shown.")
	public ResponseEntity<List<TutorView>> aboutMe(CurrentUser currentUser) {
		return ResponseEntity.ok(service.aboutTutor(currentUser.userId()).stream()
				.map(TutorView::from)
				.toList());
	}

	@PostMapping("/{id}/reply")
	@Operation(
			summary = "Reply to a review",
			description = "One reply per review, on a published review only. It is moderated "
					+ "before it appears.")
	public ResponseEntity<TutorView> reply(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody ReplyRequest request) {

		return ResponseEntity.ok(
				TutorView.from(service.reply(currentUser.userId(), id, request.text())));
	}
}
