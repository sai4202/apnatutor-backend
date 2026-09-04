package com.apnatutor.admin;

import com.apnatutor.admin.dto.MetricsDtos.Dashboard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Funnel metrics - M5-05.7.
 *
 * <p>One endpoint, not one per number. The whole dashboard is a single snapshot in a single
 * transaction; splitting it into a call per tile would let the counts disagree with each other, and
 * would trade one query for eight on a screen nobody keeps open.
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin - metrics", description = "Signups, enquiries, leads and revenue")
public class AdminMetricsController {

	private final AdminMetricsService service;

	public AdminMetricsController(AdminMetricsService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
			summary = "The dashboard",
			description = "Totals are all-time; the daily series covers the requested window, "
					+ "bucketed in Asia/Kolkata with empty days included as zeros. Every rate is "
					+ "returned as a numerator and a denominator, because on a young platform "
					+ "'8%' hides that it means two out of twenty-five.")
	public ResponseEntity<Dashboard> dashboard(
			@RequestParam(defaultValue = "28") int windowDays) {
		return ResponseEntity.ok(service.dashboard(windowDays));
	}
}
