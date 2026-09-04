package com.apnatutor.user;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.user.UserAdminService.SuspensionResult;
import com.apnatutor.user.domain.UserRole;
import com.apnatutor.user.domain.UserStatus;
import com.apnatutor.user.dto.AdminUserDtos.SuspendRequest;
import com.apnatutor.user.dto.AdminUserDtos.SuspensionOutcome;
import com.apnatutor.user.dto.AdminUserDtos.UserDetail;
import com.apnatutor.user.dto.AdminUserDtos.UserRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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
 * Account management - M5-05.3.
 *
 * <p>Class-level {@code @PreAuthorize}, following the other admin controllers: a method added later
 * is protected by default rather than by whoever adds it remembering.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin - users", description = "Finding accounts, suspending and reinstating them")
public class AdminUserController {

	/** Capped so a caller cannot ask for the whole table in one request. */
	private static final int MAX_PAGE_SIZE = 100;

	private final UserAdminService service;

	public AdminUserController(UserAdminService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "Find accounts",
			description = "Newest first. Every filter is optional; phone matches on any fragment, "
					+ "because a caller reads out the last few digits rather than the +91 form.")
	public ResponseEntity<PageResponse<UserRow>> search(
			@RequestParam(required = false) UserRole role,
			@RequestParam(required = false) UserStatus status,
			@RequestParam(required = false) String phone,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok(PageResponse.from(
				service.search(role, status, phone, PageRequest.of(Math.max(page, 0), clamp(size))),
				UserRow::from));
	}

	@GetMapping("/{id}")
	@Operation(
			summary = "One account, with the activity that explains it",
			description = "Wallet, leads and disputes for a tutor; enquiries posted, live and "
					+ "removed for a student. The counts are what make a suspension decision "
					+ "possible without opening four other screens.")
	public ResponseEntity<UserDetail> detail(@PathVariable Long id) {
		return ResponseEntity.ok(service.detail(id));
	}

	@PostMapping("/{id}/suspend")
	@Operation(
			summary = "Block an account",
			description = "The reason is mandatory and is shown to the user. Suspending a student "
					+ "also takes down their live enquiries, refunding the tutors who had paid for "
					+ "them; the response says how many. Admin accounts cannot be suspended here.")
	public ResponseEntity<SuspensionOutcome> suspend(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody SuspendRequest request) {

		SuspensionResult result = service.suspend(id, currentUser.userId(), request.reason());

		return ResponseEntity.ok(
				new SuspensionOutcome(result.user(), result.enquiriesRemoved()));
	}

	@PostMapping("/{id}/reinstate")
	@Operation(
			summary = "Lift a suspension",
			description = "Enquiries removed by the suspension are not restored - their tutors "
					+ "have been refunded and told the enquiry was gone. Posting again is free.")
	public ResponseEntity<UserDetail> reinstate(CurrentUser currentUser, @PathVariable Long id) {
		return ResponseEntity.ok(service.reinstate(id, currentUser.userId()));
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
