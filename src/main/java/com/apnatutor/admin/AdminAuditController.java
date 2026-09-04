package com.apnatutor.admin;

import com.apnatutor.admin.dto.AuditDtos.AuditRow;
import com.apnatutor.audit.AuditService;
import com.apnatutor.audit.domain.AuditOutcome;
import com.apnatutor.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the audit log — {@code M5-08.4}.
 *
 * <p>Read-only, and there is no write endpoint anywhere: entries are made by
 * {@link com.apnatutor.audit.AuditInterceptor} as a consequence of acting, never by asking. An API
 * that can add an audit entry is an API that can add a false one.
 *
 * <p>Note that reading this is itself audited — every {@code GET} here is under {@code /admin}, so
 * it lands in the log like anything else. That is deliberate: who has been reading the record of
 * who did what is a reasonable question to be able to answer.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — audit", description = "Who did what, and what it changed")
public class AdminAuditController {

	private static final int MAX_PAGE_SIZE = 100;

	private final AuditService audit;

	public AdminAuditController(AuditService audit) {
		this.audit = audit;
	}

	@GetMapping
	@Operation(
			summary = "The audit log, newest first",
			description = "Every filter is optional. Filtering by target answers the question "
					+ "actually asked in support: \"what has been done to this account?\" — pass "
					+ "targetType USER with the account's id.")
	public ResponseEntity<PageResponse<AuditRow>> search(
			@RequestParam(required = false) Long actorId,
			@RequestParam(required = false) String targetType,
			@RequestParam(required = false) Long targetId,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) AuditOutcome outcome,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {

		return ResponseEntity.ok(PageResponse.from(
				audit.search(
						actorId,
						targetType,
						targetId,
						action,
						outcome,
						PageRequest.of(Math.max(page, 0), clamp(size))),
				AuditRow::from));
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
