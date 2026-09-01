package com.apnatutor.verification;

import java.io.IOException;
import java.util.List;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.verification.domain.VerificationLevel;
import com.apnatutor.verification.domain.VerificationType;
import com.apnatutor.verification.dto.VerificationDtos.OwnerView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A tutor submitting their own documents for verification.
 *
 * <p>Like the profile endpoints, everything here operates on {@code currentUser.userId()} and never
 * on an id from the request, so one tutor cannot submit documents against another's account.
 */
@RestController
@RequestMapping("/api/v1/tutor/verification")
@PreAuthorize("hasRole('TUTOR')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Verification", description = "Submitting documents for review")
public class VerificationController {

	private final VerificationService service;

	public VerificationController(VerificationService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "My verification status",
			description = "Every request I have made, with the reason for any rejection.")
	public ResponseEntity<List<OwnerView>> myVerifications(CurrentUser currentUser) {
		return ResponseEntity.ok(service.forUser(currentUser.userId()).stream()
				.map(OwnerView::from)
				.toList());
	}

	@GetMapping("/level")
	@Operation(
			summary = "My trust level",
			description = "PHONE_VERIFIED on registration, ID_VERIFIED once an admin approves a "
					+ "government ID, FULLY_VERIFIED with education approved too.")
	public ResponseEntity<VerificationLevel> myLevel(CurrentUser currentUser) {
		return ResponseEntity.ok(service.levelFor(currentUser.userId()));
	}

	@PostMapping(value = "/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(
			summary = "Submit a document for verification",
			description = "`type` is ID or EDUCATION. JPG, PNG or PDF up to 10 MB. The document is "
					+ "stored privately and is only ever readable by an admin reviewer.")
	public ResponseEntity<OwnerView> submit(
			CurrentUser currentUser,
			@PathVariable VerificationType type,
			@RequestParam("file") MultipartFile file) throws IOException {

		if (file == null || file.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a document to upload.");
		}

		return ResponseEntity.ok(OwnerView.from(
				service.submitDocument(currentUser.userId(), type, file.getBytes())));
	}
}
