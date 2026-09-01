package com.apnatutor.settings;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.settings.domain.PlatformSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and updates the admin-configurable platform numbers.
 *
 * <p>These were Java constants until the numbers turned out to be a hypothesis rather than a
 * finding. Credit prices, the unlock cap and the online discount all need tuning against real tutor
 * behaviour, and a pricing change that requires a release is a pricing change that does not happen.
 *
 * <h2>Why there is no cache</h2>
 *
 * <p>Deliberately reads the database each time. These are single-row primary-key lookups on a table
 * with nine rows, which Postgres serves from its own buffer cache — a Spring cache on top would add
 * an invalidation problem in exchange for microseconds. Revisit only if a profiler says so.
 */
@Service
public class SettingsService {

	private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

	// Keys are constants rather than loose strings, so a typo is a compile error rather than a
	// silent fallback to a default.
	public static final String UNLOCK_CAP = "lead.unlock_cap";
	public static final String ONLINE_MULTIPLIER = "lead.online_multiplier";
	public static final String CREDIT_VALUE_PAISE = "credits.value_paise";
	public static final String SIGNUP_BONUS = "credits.signup_bonus";
	public static final String PURCHASED_VALIDITY_DAYS = "credits.purchased_validity_days";
	public static final String BONUS_VALIDITY_DAYS = "credits.bonus_validity_days";
	public static final String LOW_BALANCE_THRESHOLD = "credits.low_balance_threshold";
	public static final String REQUIREMENT_LIFETIME_DAYS = "requirements.lifetime_days";
	public static final String REFUND_WINDOW_DAYS = "refunds.window_days";

	private final PlatformSettingRepository settings;

	public SettingsService(PlatformSettingRepository settings) {
		this.settings = settings;
	}

	@Transactional(readOnly = true)
	public List<PlatformSetting> all() {
		return settings.findAllByOrderByKeyAsc();
	}

	/**
	 * An integer setting.
	 *
	 * <p>Falls back to the supplied default if the row is missing — a settings table that has not
	 * been seeded should not take the whole application down, and the log line says which key was
	 * absent.
	 */
	@Transactional(readOnly = true)
	public int intValue(String key, int fallback) {
		return settings.findById(key)
				.map(PlatformSetting::asInt)
				.orElseGet(() -> {
					log.warn("Setting '{}' not found; using default {}", key, fallback);
					return fallback;
				});
	}

	@Transactional(readOnly = true)
	public BigDecimal decimalValue(String key, BigDecimal fallback) {
		return settings.findById(key)
				.map(PlatformSetting::asDecimal)
				.orElseGet(() -> {
					log.warn("Setting '{}' not found; using default {}", key, fallback);
					return fallback;
				});
	}

	public Duration durationDays(String key, int fallbackDays) {
		return Duration.ofDays(intValue(key, fallbackDays));
	}

	/**
	 * Updates a setting.
	 *
	 * @throws ApiException with {@code VALIDATION_FAILED} if the value is the wrong type or outside
	 *     the setting's own bounds
	 */
	@Transactional
	public PlatformSetting update(String key, String newValue, Long adminUserId) {
		PlatformSetting setting = settings.findById(key)
				.orElseThrow(() -> ApiException.notFound("Setting '" + key + "'"));

		String previous = setting.getValue();

		try {
			setting.update(newValue, adminUserId);
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
		}

		settings.save(setting);

		// Logged with the old value. A pricing change is a commercial decision someone may need to
		// reconstruct months later, and "it was 5 before" is the part that gets forgotten.
		log.info("Setting changed: key={} from={} to={} by admin={}",
				key, previous, setting.getValue(), adminUserId);

		return setting;
	}
}
