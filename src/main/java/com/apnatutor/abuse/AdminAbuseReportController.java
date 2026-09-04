package com.apnatutor.abuse;

import com.apnatutor.abuse.domain.AbuseReport;
import com.apnatutor.abuse.domain.ReportStatus;
import com.apnatutor.abuse.dto.AbuseDtos.DecisionRequest;
import com.apnatutor.abuse.dto.AbuseDtos.TriageView;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
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
 * The triage queue — {@code M5-09.2}.
 *
 * <p>Deciding a report and acting on it are separate on purpose. Upholding says a moderator agreed;
 * suspending the account, taking the enquiry down or unpublishing the review is done from the
 * screen that owns that action, with its own rules and its own audit entry. Wiring them together
 * would turn a handful of coordinated reports into a way to remove a competitor.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — reports", description = "Abuse triage")
public class AdminAbuseReportController {

	private static final int MAX_PAGE_SIZE = 100;

	private final AbuseReportService service;

	public AdminAbuseReportController(AbuseReportService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "Open reports, oldest first",
			description = "Oldest first because a report left sitting is somebody concluding we "
					+ "do not care. Pass decided=true to see everything instead.")
	public ResponseEntity<PageResponse<TriageView>> queue(
			@RequestParam(defaultValue = "false") boolean decided,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		PageRequest pageable = PageRequest.of(Math.max(page, 0), clamp(size));

		return ResponseEntity.ok(PageResponse.from(
				decided ? service.all(pageable) : service.queue(pageable),
				this::withContext));
	}

	@PostMapping("/{id}/uphold")
	@Operation(
			summary = "Agree with a report",
			description = "Records the judgement and tells the reporter. It does not suspend, "
					+ "remove or unpublish anything — do that from the screen that owns the action.")
	public ResponseEntity<TriageView> uphold(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody DecisionRequest request) {

		return ResponseEntity.ok(withContext(
				service.decide(id, ReportStatus.UPHELD, currentUser.userId(), request.note())));
	}

	@PostMapping("/{id}/dismiss")
	@Operation(
			summary = "Find nothing to act on",
			description = "The reporter is told, with the note. Silence would teach them that "
					+ "reporting does nothing, and they are usually the only witness.")
	public ResponseEntity<TriageView> dismiss(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody DecisionRequest request) {

		return ResponseEntity.ok(withContext(
				service.decide(id, ReportStatus.DISMISSED, currentUser.userId(), request.note())));
	}

	/**
	 * Attaches the counts that make a report readable.
	 *
	 * <p>Two extra queries per row. The queue is short by construction — it is a list of things
	 * gone wrong — and if it is ever long enough for that to matter, the query count is not the
	 * problem to fix.
	 */
	private TriageView withContext(AbuseReport report) {
		return TriageView.from(
				report,
				service.distinctReportersFor(report.getSubjectType(), report.getSubjectId()),
				service.about(report.getSubjectType(), report.getSubjectId()).size());
	}

	private static int clamp(int size) {
		return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
	}
}
