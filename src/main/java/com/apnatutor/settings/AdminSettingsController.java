package com.apnatutor.settings;

import java.math.BigDecimal;
import java.util.List;

import com.apnatutor.common.security.CurrentUser;
import com.apnatutor.lead.LeadPricingService;
import com.apnatutor.lead.domain.LeadPricingBand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin control over pricing and the platform numbers.
 *
 * <p>These were Java constants until it became clear they were a hypothesis rather than a finding.
 * Credit prices, the unlock cap and the online discount all need tuning against real behaviour, and
 * a pricing change that requires a release is a pricing change that does not happen.
 *
 * <h2>What a change does and does not affect</h2>
 *
 * <p>Repricing applies to <strong>new enquiries only</strong>. Both the price and the unlock cap are
 * locked onto each requirement when it is posted, so a tutor is never charged more than the figure
 * they were shown, and a parent promised at most five calls does not silently start receiving seven.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Admin — settings", description = "Pricing and platform configuration")
public class AdminSettingsController {

	@Schema(description = "A configurable platform number, with its permitted range")
	public record SettingView(
			String key,
			String value,
			String valueType,
			String description,
			BigDecimal minValue,
			BigDecimal maxValue) {
	}

	@Schema(description = "A new value for one setting")
	public record UpdateSettingRequest(
			@NotBlank(message = "Give a value") @Size(max = 200) String value) {
	}

	@Schema(description = "One rung of the lead pricing ladder")
	public record BandView(Long id, long minBudgetPaise, int credits, String label) {

		static BandView from(LeadPricingBand band) {
			return new BandView(
					band.getId(), band.getMinBudgetPaise(), band.getCredits(), band.getLabel());
		}
	}

	@Schema(description = "Change what a band costs")
	public record UpdateBandRequest(
			@Min(value = 1, message = "A band must cost at least 1 credit — a free lead gives away the business model")
			int credits,
			@NotBlank @Size(max = 60) String label) {
	}

	@Schema(description = "Add a rung to the pricing ladder")
	public record AddBandRequest(
			@PositiveOrZero long minBudgetPaise,
			@Min(1) int credits,
			@NotBlank @Size(max = 60) String label) {
	}

	private final SettingsService settings;
	private final LeadPricingService pricing;

	public AdminSettingsController(SettingsService settings, LeadPricingService pricing) {
		this.settings = settings;
		this.pricing = pricing;
	}

	@GetMapping
	@Operation(
			summary = "All platform settings",
			description = "Each carries its own permitted range — an unlock cap of 500 is not a "
					+ "configuration choice, it is an outage for every parent who posts.")
	public ResponseEntity<List<SettingView>> allSettings() {
		return ResponseEntity.ok(settings.all().stream()
				.map(setting -> new SettingView(
						setting.getKey(),
						setting.getValue(),
						setting.getValueType(),
						setting.getDescription(),
						setting.getMinValue(),
						setting.getMaxValue()))
				.toList());
	}

	@PutMapping("/{key}")
	@Operation(
			summary = "Change a setting",
			description = "Validated against that setting's own bounds before it is stored, so a "
					+ "bad value never reaches the code depending on it. The change is logged with "
					+ "the previous value.")
	public ResponseEntity<SettingView> updateSetting(
			CurrentUser currentUser,
			@PathVariable String key,
			@Valid @RequestBody UpdateSettingRequest request) {

		var updated = settings.update(key, request.value(), currentUser.userId());
		return ResponseEntity.ok(new SettingView(
				updated.getKey(),
				updated.getValue(),
				updated.getValueType(),
				updated.getDescription(),
				updated.getMinValue(),
				updated.getMaxValue()));
	}

	@GetMapping("/pricing/bands")
	@Operation(
			summary = "The lead pricing ladder",
			description = "Each band is an inclusive lower bound; a budget is priced by the "
					+ "highest band it meets.")
	public ResponseEntity<List<BandView>> bands() {
		return ResponseEntity.ok(pricing.allBands().stream().map(BandView::from).toList());
	}

	@PutMapping("/pricing/bands/{id}")
	@Operation(
			summary = "Reprice a band",
			description = "Applies to new enquiries only. Existing ones keep the price they were "
					+ "posted with, so no tutor is charged more than the figure they were shown.")
	public ResponseEntity<BandView> updateBand(
			CurrentUser currentUser,
			@PathVariable Long id,
			@Valid @RequestBody UpdateBandRequest request) {

		return ResponseEntity.ok(BandView.from(
				pricing.updateBand(id, request.credits(), request.label(), currentUser.userId())));
	}

	@PostMapping("/pricing/bands")
	@Operation(summary = "Add a band to the ladder")
	public ResponseEntity<BandView> addBand(
			CurrentUser currentUser, @Valid @RequestBody AddBandRequest request) {

		return ResponseEntity.ok(BandView.from(pricing.addBand(
				request.minBudgetPaise(), request.credits(), request.label(),
				currentUser.userId())));
	}

	@DeleteMapping("/pricing/bands/{id}")
	@Operation(
			summary = "Remove a band",
			description = "The lowest band cannot be removed — every budget needs a price.")
	public ResponseEntity<Void> removeBand(CurrentUser currentUser, @PathVariable Long id) {
		pricing.removeBand(id, currentUser.userId());
		return ResponseEntity.noContent().build();
	}
}
