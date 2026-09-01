package com.apnatutor.billing;

import java.util.List;

import com.apnatutor.billing.domain.CreditPackage;
import com.apnatutor.billing.dto.BillingDtos.PackageRequest;
import com.apnatutor.billing.dto.BillingDtos.PackageView;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.common.web.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing credit packages without a deploy.
 *
 * <p>The launch prices are a hypothesis (PENDING.md D3), and the only honest way to hold a
 * hypothesis is somewhere it can be changed when the data arrives.
 *
 * <p>A repricing only ever affects future purchases: {@code payments} copies the credits and amount
 * at order time rather than reading them through the foreign key, so a receipt cannot be rewritten
 * retroactively.
 */
@RestController
@RequestMapping("/api/v1/admin/packages")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — packages", description = "Credit package pricing")
public class AdminPackageController {

	private static final Logger log = LoggerFactory.getLogger(AdminPackageController.class);

	private final CreditPackageRepository packages;

	public AdminPackageController(CreditPackageRepository packages) {
		this.packages = packages;
	}

	@GetMapping
	@Operation(summary = "Every package, retired ones included")
	public ResponseEntity<List<PackageView>> list() {
		return ResponseEntity.ok(
				packages.findAllByOrderBySortOrderAscIdAsc().stream()
						.map(PackageView::from)
						.toList());
	}

	@PostMapping
	@Transactional
	@Operation(summary = "Add a package")
	public ResponseEntity<PackageView> create(
			CurrentUser currentUser, @Valid @RequestBody PackageRequest request) {

		CreditPackage created = packages.save(build(request));

		log.info("Package created: id={} name={} credits={} price={} by admin={}",
				created.getId(), created.getName(), created.getCredits(),
				created.getPricePaise(), currentUser.userId());

		return ResponseEntity.ok(PackageView.from(created));
	}

	@PutMapping("/{id}")
	@Transactional
	@Operation(
			summary = "Reprice a package",
			description = "Affects future purchases only. Existing payments keep what they were "
					+ "charged.")
	public ResponseEntity<PackageView> update(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody PackageRequest request) {

		CreditPackage existing = packages.findById(id)
				.orElseThrow(() -> ApiException.notFound("Package"));

		// Logged with the old figures. A repricing is a commercial decision someone may need to
		// reconstruct months later, and the previous price is the part that gets forgotten.
		log.info("Package repriced: id={} from={}cr/{}p to={}cr/{}p by admin={}",
				id, existing.getCredits(), existing.getPricePaise(),
				request.credits(), request.pricePaise(), currentUser.userId());

		try {
			existing.update(
					request.name(),
					request.credits(),
					request.pricePaise(),
					Boolean.TRUE.equals(request.highlighted()),
					request.sortOrder() == null ? existing.getSortOrder() : request.sortOrder());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		return ResponseEntity.ok(PackageView.from(packages.save(existing)));
	}

	@DeleteMapping("/{id}")
	@Transactional
	@Operation(
			summary = "Retire a package",
			description = "Hidden from the storefront but kept, so historical payments still "
					+ "resolve what they bought.")
	public ResponseEntity<PackageView> retire(CurrentUser currentUser, @PathVariable Long id) {
		CreditPackage existing = packages.findById(id)
				.orElseThrow(() -> ApiException.notFound("Package"));

		// A storefront with nothing on it is a checkout that cannot be completed, which looks like
		// an outage rather than a pricing decision.
		if (existing.isActive() && packages.countByActiveTrue() <= 1) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"This is the last active package. Add another before retiring it.");
		}

		existing.retire();
		log.info("Package retired: id={} by admin={}", id, currentUser.userId());

		return ResponseEntity.ok(PackageView.from(packages.save(existing)));
	}

	@PostMapping("/{id}/restore")
	@Transactional
	@Operation(summary = "Put a retired package back on the storefront")
	public ResponseEntity<PackageView> restore(CurrentUser currentUser, @PathVariable Long id) {
		CreditPackage existing = packages.findById(id)
				.orElseThrow(() -> ApiException.notFound("Package"));

		existing.restore();
		log.info("Package restored: id={} by admin={}", id, currentUser.userId());

		return ResponseEntity.ok(PackageView.from(packages.save(existing)));
	}

	private static CreditPackage build(PackageRequest request) {
		try {
			return CreditPackage.of(
					request.name(),
					request.credits(),
					request.pricePaise(),
					Boolean.TRUE.equals(request.highlighted()),
					request.sortOrder() == null ? 0 : request.sortOrder());
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}
	}
}
