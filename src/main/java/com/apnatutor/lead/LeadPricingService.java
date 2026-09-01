package com.apnatutor.lead;

import java.util.List;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.lead.domain.LeadPricingBand;
import com.apnatutor.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the configured pricing and applies it.
 *
 * <p>A thin layer over {@link LeadPricing}, which stays a pure function so the arithmetic can be
 * tested exhaustively without a database.
 */
@Service
public class LeadPricingService {

	private static final Logger log = LoggerFactory.getLogger(LeadPricingService.class);

	private final LeadPricingBandRepository bands;
	private final SettingsService settings;

	public LeadPricingService(LeadPricingBandRepository bands, SettingsService settings) {
		this.bands = bands;
		this.settings = settings;
	}

	@Transactional(readOnly = true)
	public int creditsFor(Long budgetPaise, String mode) {
		return LeadPricing.creditsFor(
				budgetPaise,
				mode,
				bands.findAllByOrderByMinBudgetPaiseAsc(),
				settings.decimalValue(
						SettingsService.ONLINE_MULTIPLIER, LeadPricing.DEFAULT_ONLINE_MULTIPLIER));
	}

	/** The cap to lock onto a new requirement. Existing ones keep the cap they were posted with. */
	@Transactional(readOnly = true)
	public int currentUnlockCap() {
		return settings.intValue(SettingsService.UNLOCK_CAP, 5);
	}

	@Transactional(readOnly = true)
	public List<LeadPricingBand> allBands() {
		return bands.findAllByOrderByMinBudgetPaiseAsc();
	}

	@Transactional
	public LeadPricingBand updateBand(Long bandId, int credits, String label, Long adminUserId) {
		LeadPricingBand band = bands.findById(bandId)
				.orElseThrow(() -> ApiException.notFound("Pricing band"));

		int previous = band.getCredits();
		try {
			band.update(credits, label);
		} catch (IllegalArgumentException e) {
			throw new ApiException(
					com.apnatutor.common.web.ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		bands.save(band);

		// Logged with the old value: a repricing is a commercial decision someone may need to
		// reconstruct later, and the previous figure is the part that gets forgotten.
		log.info("Lead pricing changed: band={} from={} to={} credits by admin={}",
				band.getMinBudgetPaise(), previous, credits, adminUserId);

		return band;
	}

	@Transactional
	public LeadPricingBand addBand(long minBudgetPaise, int credits, String label, Long adminUserId) {
		if (bands.existsByMinBudgetPaise(minBudgetPaise)) {
			throw new ApiException(com.apnatutor.common.web.ErrorCode.CONFLICT,
					"A band already starts at that budget.");
		}
		if (credits <= 0) {
			throw new ApiException(com.apnatutor.common.web.ErrorCode.VALIDATION_FAILED,
					"A band must cost at least 1 credit.");
		}

		LeadPricingBand band = bands.save(new LeadPricingBand(minBudgetPaise, credits, label));
		log.info("Lead pricing band added: from={} paise at {} credits by admin={}",
				minBudgetPaise, credits, adminUserId);
		return band;
	}

	@Transactional
	public void removeBand(Long bandId, Long adminUserId) {
		LeadPricingBand band = bands.findById(bandId)
				.orElseThrow(() -> ApiException.notFound("Pricing band"));

		if (band.getMinBudgetPaise() == 0L) {
			// The zero band is what every unstated or tiny budget falls into. Removing it would
			// leave the lowest budgets with no band at all.
			throw new ApiException(com.apnatutor.common.web.ErrorCode.CONFLICT,
					"The lowest band cannot be removed — every budget needs a price.");
		}

		bands.delete(band);
		log.info("Lead pricing band removed: from={} paise by admin={}",
				band.getMinBudgetPaise(), adminUserId);
	}
}
