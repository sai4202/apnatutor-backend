package com.apnatutor.requirement;

import java.util.List;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.requirement.dto.RequirementDtos.PostRequirementRequest;
import com.apnatutor.requirement.dto.RequirementDtos.PriceQuote;
import com.apnatutor.requirement.dto.RequirementDtos.StudentView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A student's own requirements.
 *
 * <p>Every route acts on {@code currentUser.userId()}. There is no endpoint taking a student id, so
 * no parameter tampering can reach another family's enquiry — which contains their child's class,
 * their timings and their address area.
 */
@RestController
@RequestMapping("/api/v1/student/requirements")
@PreAuthorize("hasRole('STUDENT')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Requirements", description = "Posting and managing tuition enquiries")
public class RequirementController {

	private final RequirementService service;
	private final RequirementViewMapper mapper;

	public RequirementController(RequirementService service, RequirementViewMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@PostMapping
	@Operation(
			summary = "Post a requirement",
			description = "Free. At most five tutors will be able to respond, so your phone does "
					+ "not turn into a call centre.")
	public ResponseEntity<StudentView> post(
			CurrentUser currentUser, @Valid @RequestBody PostRequirementRequest request) {

		var requirement = service.post(currentUser.userId(), request);
		return ResponseEntity.ok(mapper.toStudentView(requirement, mapper.loadCatalog()));
	}

	@GetMapping
	@Operation(summary = "My requirements")
	public ResponseEntity<List<StudentView>> myRequirements(CurrentUser currentUser) {
		var catalog = mapper.loadCatalog();
		return ResponseEntity.ok(service.forStudent(currentUser.userId()).stream()
				.map(requirement -> mapper.toStudentView(requirement, catalog))
				.toList());
	}

	@GetMapping("/{id}")
	@Operation(
			summary = "One requirement, with the tutors who responded",
			description = "Responding tutors' phone numbers are shown — they spent credits "
					+ "specifically to start this conversation.")
	public ResponseEntity<StudentView> one(CurrentUser currentUser, @PathVariable Long id) {
		var requirement = service.ownedBy(id, currentUser.userId());
		return ResponseEntity.ok(mapper.toStudentView(requirement, mapper.loadCatalog()));
	}

	@PostMapping("/{id}/hired")
	@Operation(
			summary = "Mark as hired",
			description = "Closes the enquiry. Tutors stop being able to respond.")
	public ResponseEntity<StudentView> markHired(CurrentUser currentUser, @PathVariable Long id) {
		var requirement = service.markHired(id, currentUser.userId());
		return ResponseEntity.ok(mapper.toStudentView(requirement, mapper.loadCatalog()));
	}

	@PostMapping("/{id}/close")
	@Operation(summary = "Withdraw the enquiry")
	public ResponseEntity<StudentView> close(CurrentUser currentUser, @PathVariable Long id) {
		var requirement = service.close(id, currentUser.userId());
		return ResponseEntity.ok(mapper.toStudentView(requirement, mapper.loadCatalog()));
	}

	@GetMapping("/quote")
	@Operation(
			summary = "What this enquiry will cost a tutor",
			description = "Shown for transparency. Students never pay — this is what a tutor "
					+ "spends to reach you, and it scales with the stated budget.")
	public ResponseEntity<PriceQuote> quote(
			@RequestParam(required = false) Long budgetAmountPaise,
			@RequestParam(defaultValue = "STUDENT_HOME") String mode) {

		int credits = service.quotePrice(budgetAmountPaise, mode);
		return ResponseEntity.ok(new PriceQuote(
				credits,
				"A tutor spends %d credits to see your contact details. You never pay anything."
						.formatted(credits)));
	}
}
