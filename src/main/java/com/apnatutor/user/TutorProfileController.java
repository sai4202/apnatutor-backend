package com.apnatutor.user;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.user.dto.TutorProfileDtos.OwnerView;
import com.apnatutor.user.dto.TutorProfileDtos.PublicView;
import com.apnatutor.user.dto.TutorProfileDtos.QualificationRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateBasicsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateFeesRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateSubjectsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateTeachingRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tutor profile management, for the tutor who owns it.
 *
 * <p>Every method is {@code TUTOR}-only and operates on {@code currentUser.userId()} — never on an
 * id from the request. There is deliberately no "update tutor {id}" endpoint: without one, no
 * amount of parameter tampering lets one tutor edit another's profile. That closes the ownership
 * question by construction rather than by a check that a future edit could omit (M1-05.3).
 *
 * <p>The public read lives in {@link #publicProfile}, under {@code /public/}, and returns a
 * different type that has no contact fields on it at all.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tutor profile", description = "Create and manage a tutor profile")
public class TutorProfileController {

	private final TutorProfileService service;

	public TutorProfileController(TutorProfileService service) {
		this.service = service;
	}

	@GetMapping("/tutor/profile")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(
			summary = "My profile",
			description = "Creates an empty profile on first call. Includes a completeness score "
					+ "and a plain-language list of what still needs filling in.")
	public ResponseEntity<OwnerView> myProfile(CurrentUser currentUser) {
		return ResponseEntity.ok(service.getOwnProfile(currentUser.userId()));
	}

	@PutMapping("/tutor/profile/basics")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Update name, headline, bio and experience")
	public ResponseEntity<OwnerView> updateBasics(
			CurrentUser currentUser, @Valid @RequestBody UpdateBasicsRequest request) {
		return ResponseEntity.ok(service.updateBasics(currentUser.userId(), request));
	}

	@PutMapping("/tutor/profile/fees")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Update fees", description = "Amounts are in paise — ₹4,500 is 450000.")
	public ResponseEntity<OwnerView> updateFees(
			CurrentUser currentUser, @Valid @RequestBody UpdateFeesRequest request) {
		return ResponseEntity.ok(service.updateFees(currentUser.userId(), request));
	}

	@PutMapping("/tutor/profile/teaching")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Update teaching modes, travel radius and serviceable areas")
	public ResponseEntity<OwnerView> updateTeaching(
			CurrentUser currentUser, @Valid @RequestBody UpdateTeachingRequest request) {
		return ResponseEntity.ok(service.updateTeaching(currentUser.userId(), request));
	}

	@PutMapping("/tutor/profile/subjects")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(
			summary = "Replace the subject list",
			description = "Only leaf subjects are accepted — a category such as 'School Tuition' "
					+ "is for navigation, not something a tutor teaches.")
	public ResponseEntity<OwnerView> updateSubjects(
			CurrentUser currentUser, @Valid @RequestBody UpdateSubjectsRequest request) {
		return ResponseEntity.ok(service.updateSubjects(currentUser.userId(), request));
	}

	@PostMapping("/tutor/profile/qualifications")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Add a qualification")
	public ResponseEntity<OwnerView> addQualification(
			CurrentUser currentUser, @Valid @RequestBody QualificationRequest request) {
		return ResponseEntity.ok(service.addQualification(currentUser.userId(), request));
	}

	@DeleteMapping("/tutor/profile/qualifications/{id}")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Remove a qualification")
	public ResponseEntity<OwnerView> removeQualification(
			CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(service.removeQualification(currentUser.userId(), id));
	}

	@PostMapping("/tutor/profile/publish")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(
			summary = "Publish the profile",
			description = "Fails with PROFILE_INCOMPLETE below 60% completeness. An empty profile "
					+ "in search wastes a parent's time and reflects on every other tutor.")
	public ResponseEntity<OwnerView> publish(CurrentUser currentUser) {
		return ResponseEntity.ok(service.publish(currentUser.userId()));
	}

	@PostMapping("/tutor/profile/unpublish")
	@PreAuthorize("hasRole('TUTOR')")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "Hide the profile from search")
	public ResponseEntity<OwnerView> unpublish(CurrentUser currentUser) {
		return ResponseEntity.ok(service.unpublish(currentUser.userId()));
	}

	@GetMapping("/public/tutors/{id}")
	@Operation(
			summary = "A tutor's public profile",
			description = "Published profiles only. Returns no contact details of any kind — those "
					+ "are what tutors pay credits to unlock.")
	public ResponseEntity<PublicView> publicProfile(@PathVariable Long id) {
		return ResponseEntity.ok(service.getPublicProfile(id));
	}
}
