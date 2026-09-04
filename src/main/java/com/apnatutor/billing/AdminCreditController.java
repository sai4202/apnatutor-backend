package com.apnatutor.billing;

import com.apnatutor.audit.AuditContext;
import com.apnatutor.billing.domain.CreditReason;
import com.apnatutor.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin credit adjustments.
 *
 * <p>Two purposes. It is the sanctioned way to correct a balance — a compensating ledger entry
 * rather than an UPDATE, which the database refuses anyway. And it is what lets the entire M3 lead
 * loop be exercised before any payment code exists: grant a tutor credits, watch them unlock a lead.
 *
 * <p>Every grant records which admin made it. A person handing out credits should expect to be able
 * to explain why.
 */
@RestController
@RequestMapping("/api/v1/admin/credits")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — credits", description = "Granting and correcting credit balances")
public class AdminCreditController {

	private static final Logger log = LoggerFactory.getLogger(AdminCreditController.class);

	@Schema(description = "A manual credit grant")
	public record GrantRequest(
			@NotNull Long tutorUserId,
			@Schema(description = "Credits to add. Capped to keep a slipped digit from becoming an incident.")
			@Min(1) @Max(10_000) int credits,
			@Schema(example = "Goodwill credit after a support issue")
			@NotBlank(message = "Say why — an unexplained grant is indistinguishable from a mistake")
			@Size(max = 300) String reason) {
	}

	@Schema(description = "A tutor's balance")
	public record BalanceView(Long tutorUserId, int balance, int ledgerDifference) {
	}

	private final CreditLedger ledger;

	public AdminCreditController(CreditLedger ledger) {
		this.ledger = ledger;
	}

	@PostMapping("/grant")
	@Operation(
			summary = "Grant credits to a tutor",
			description = "Writes an ADMIN_ADJUSTMENT ledger entry. This is the only sanctioned "
					+ "way to change a balance — the ledger itself cannot be edited.")
	public ResponseEntity<BalanceView> grant(
			CurrentUser currentUser, @Valid @RequestBody GrantRequest request) {

		// Read before the grant. A balance is the one thing here nobody can reconstruct later:
		// the ledger says the credits arrived, and only this says what they landed on top of.
		int balanceBefore = ledger.balanceOf(request.tutorUserId());

		ledger.grant(
				request.tutorUserId(),
				request.credits(),
				CreditReason.ADMIN_ADJUSTMENT,
				"ADMIN_GRANT",
				currentUser.userId(),
				// No expiry on a manual grant: these are usually goodwill after a support problem,
				// and expiring an apology would be a second insult.
				null);

		// M5-08.3: every credit adjustment recorded.
		AuditContext.describe("CREDITS_GRANTED", "USER", request.tutorUserId());
		AuditContext.summarise(request.reason());
		AuditContext.before(AuditContext.fields("balance", balanceBefore));
		AuditContext.after(AuditContext.fields(
				"balance", balanceBefore + request.credits(),
				"creditsGranted", request.credits()));

		log.info("Admin credit grant: admin={} tutor={} credits={} reason={}",
				currentUser.userId(), request.tutorUserId(), request.credits(), request.reason());

		return ResponseEntity.ok(balanceView(request.tutorUserId()));
	}

	@GetMapping("/{tutorUserId}")
	@Operation(
			summary = "A tutor's balance, reconciled against the ledger",
			description = "`ledgerDifference` must be 0. Anything else means the cached balance "
					+ "and the ledger disagree, and the ledger is the one that is right.")
	public ResponseEntity<BalanceView> balance(@PathVariable Long tutorUserId) {
		return ResponseEntity.ok(balanceView(tutorUserId));
	}

	private BalanceView balanceView(Long tutorUserId) {
		return new BalanceView(
				tutorUserId, ledger.balanceOf(tutorUserId), ledger.reconcile(tutorUserId));
	}
}
