package com.apnatutor.abuse;

import java.util.List;

import com.apnatutor.abuse.dto.AbuseDtos.ReportRequest;
import com.apnatutor.abuse.dto.AbuseDtos.ReportView;
import com.apnatutor.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting something — {@code M5-09.1}.
 *
 * <p>Authenticated only. There is no anonymous report endpoint and there should not be: a report
 * button that costs nothing to use is a harassment tool, and requiring an account is most of the
 * deterrent even though it prevents nothing outright.
 */
@RestController
@RequestMapping("/api/v1/reports")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reports", description = "Reporting a tutor, student, review or enquiry")
public class AbuseReportController {

	private final AbuseReportService service;

	public AbuseReportController(AbuseReportService service) {
		this.service = service;
	}

	@PostMapping
	@Operation(
			summary = "Report something",
			description = "A report is a signal, not an instruction: nothing is suspended or "
					+ "removed automatically. A moderator looks, and whatever they then do is a "
					+ "separate decision. You will be told the outcome either way.")
	public ResponseEntity<ReportView> report(
			CurrentUser currentUser, @Valid @RequestBody ReportRequest request) {

		return ResponseEntity.ok(ReportView.from(service.report(
				currentUser.userId(),
				request.subjectType(),
				request.subjectId(),
				request.reason(),
				request.details())));
	}

	@GetMapping("/mine")
	@Operation(
			summary = "Reports you have filed, and what came of them",
			description = "Carries nothing about the reported account beyond what you sent — a "
					+ "reporter learning something new here would make this a lookup tool.")
	public ResponseEntity<List<ReportView>> mine(CurrentUser currentUser) {
		return ResponseEntity.ok(
				service.filedBy(currentUser.userId()).stream().map(ReportView::from).toList());
	}
}
