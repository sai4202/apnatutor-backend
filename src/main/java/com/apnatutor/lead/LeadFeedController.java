package com.apnatutor.lead;

import java.util.List;

import com.apnatutor.billing.CreditLedger;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.lead.domain.LeadUnlock;
import com.apnatutor.requirement.RequirementRepository;
import com.apnatutor.requirement.RequirementViewMapper;
import com.apnatutor.requirement.domain.Requirement;
import com.apnatutor.requirement.dto.RequirementDtos.LeadPreview;
import com.apnatutor.requirement.dto.RequirementDtos.UnlockRequest;
import com.apnatutor.requirement.dto.RequirementDtos.UnlockedLead;
import com.apnatutor.user.StudentProfileRepository;
import com.apnatutor.user.UserRepository;
import com.apnatutor.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tutor's side of the marketplace: browse leads, spend credits to unlock one.
 *
 * <p>The feed shows only masked previews. What a tutor pays for is the contact details, so showing
 * them for free would leave nothing to sell.
 */
@RestController
@RequestMapping("/api/v1/tutor/leads")
@PreAuthorize("hasRole('TUTOR')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Leads", description = "Browsing and unlocking student enquiries")
public class LeadFeedController {

	private static final int MAX_PAGE_SIZE = 50;

	private final RequirementRepository requirements;
	private final LeadUnlockRepository unlocks;
	private final LeadUnlockService unlockService;
	private final RequirementViewMapper mapper;
	private final CreditLedger ledger;
	private final UserRepository users;
	private final StudentProfileRepository studentProfiles;

	public LeadFeedController(
			RequirementRepository requirements,
			LeadUnlockRepository unlocks,
			LeadUnlockService unlockService,
			RequirementViewMapper mapper,
			CreditLedger ledger,
			UserRepository users,
			StudentProfileRepository studentProfiles) {
		this.requirements = requirements;
		this.unlocks = unlocks;
		this.unlockService = unlockService;
		this.mapper = mapper;
		this.ledger = ledger;
		this.users = users;
		this.studentProfiles = studentProfiles;
	}

	@GetMapping
	@Transactional(readOnly = true)
	@Operation(
			summary = "Enquiries matching what and where I teach",
			description = """
					Masked previews: subject, area, budget, timing and what it costs to unlock — \
					never a name or a phone number.

					Excludes enquiries you have already unlocked, and any that have reached five \
					responses.""")
	public ResponseEntity<PageResponse<LeadPreview>> feed(
			CurrentUser currentUser,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		var pageable = PageRequest.of(
				Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		var results = requirements.findLeadFeedFor(currentUser.userId(), pageable);
		var catalog = mapper.loadCatalog();

		return ResponseEntity.ok(
				PageResponse.from(results, requirement -> mapper.toPreview(requirement, catalog)));
	}

	@PostMapping("/{requirementId}/unlock")
	@Operation(
			summary = "Spend credits to reveal the contact details",
			description = """
					Charges the price locked onto the enquiry when it was posted — never a \
					recomputed one.

					Nothing is charged if the cap has been reached or your balance is too low; a \
					refused attempt writes no ledger entry at all.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Unlocked, contact details returned"),
			@ApiResponse(responseCode = "402", description = "INSUFFICIENT_CREDITS"),
			@ApiResponse(responseCode = "409", description = "LEAD_UNLOCK_CAP_REACHED, "
					+ "LEAD_ALREADY_UNLOCKED or REQUIREMENT_NOT_OPEN")
	})
	public ResponseEntity<UnlockedLead> unlock(
			CurrentUser currentUser,
			@PathVariable Long requirementId,
			@Valid @RequestBody(required = false) UnlockRequest request) {

		LeadUnlock unlock = unlockService.unlock(
				requirementId,
				currentUser.userId(),
				request == null ? null : request.introMessage());

		return ResponseEntity.ok(toUnlockedLead(unlock));
	}

	@GetMapping("/mine")
	@Transactional(readOnly = true)
	@Operation(summary = "Enquiries I have unlocked, with their contact details")
	public ResponseEntity<List<UnlockedLead>> myLeads(CurrentUser currentUser) {
		return ResponseEntity.ok(
				unlocks.findByTutorIdOrderByUnlockedAtDesc(currentUser.userId()).stream()
						.filter(LeadUnlock::isActive)
						.map(this::toUnlockedLead)
						.toList());
	}

	@GetMapping("/wallet")
	@Transactional(readOnly = true)
	@Operation(summary = "My credit balance and history")
	public ResponseEntity<WalletView> wallet(CurrentUser currentUser) {
		var history = ledger.history(currentUser.userId()).stream()
				.map(entry -> new WalletEntry(
						entry.getAmount(),
						entry.getReason().name(),
						entry.getBalanceAfter(),
						entry.getCreatedAt().toString()))
				.toList();

		return ResponseEntity.ok(
				new WalletView(ledger.balanceOf(currentUser.userId()), history));
	}

	public record WalletEntry(int amount, String reason, int balanceAfter, String at) {
	}

	public record WalletView(int balance, List<WalletEntry> history) {
	}

	/**
	 * Builds the post-payment view.
	 *
	 * <p>This is the one place a student's phone number is handed to a tutor, and it is reached only
	 * from records in {@code lead_unlocks} — so a contact detail cannot be returned without a paid
	 * unlock existing to justify it.
	 */
	private UnlockedLead toUnlockedLead(LeadUnlock unlock) {
		Requirement requirement = requirements.findById(unlock.getRequirementId()).orElseThrow();
		var catalog = mapper.loadCatalog();
		User student = users.findById(requirement.getStudentId()).orElse(null);

		// The name is optional — a student profile is deliberately thin and many will not have
		// filled one in. The phone always exists, because it is the account identity, and it is
		// the thing the tutor actually paid for.
		String studentName = studentProfiles.findByUserId(requirement.getStudentId())
				.map(profile -> profile.getName())
				.filter(name -> name != null && !name.isBlank())
				.orElse("Student");

		return new UnlockedLead(
				requirement.getId(),
				catalog.subjectName(requirement.getSubjectId()),
				catalog.locationName(requirement.getLocationId()),
				requirement.getMode(),
				requirement.getBudgetAmountPaise(),
				requirement.getBudgetUnit(),
				requirement.getDescription(),
				studentName,
				student == null ? null : student.getPhone(),
				unlock.getCreditsSpent(),
				unlock.getUnlockedAt());
	}
}
