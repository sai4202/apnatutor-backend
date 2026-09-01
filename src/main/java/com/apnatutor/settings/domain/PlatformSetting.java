package com.apnatutor.settings.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One admin-configurable platform number.
 *
 * <p>Each setting carries its own bounds. An unlock cap of 500 is not a configuration choice, it is
 * an outage for every parent who posts, so the range lives with the value rather than in whatever
 * screen happens to be editing it.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSetting {

	@Id
	@Column(length = 64)
	private String key;

	@Column(nullable = false, length = 200)
	private String value;

	@Column(name = "value_type", nullable = false, length = 16)
	private String valueType;

	@Column(nullable = false, length = 500)
	private String description;

	@Column(name = "min_value", precision = 12, scale = 4)
	private BigDecimal minValue;

	@Column(name = "max_value", precision = 12, scale = 4)
	private BigDecimal maxValue;

	@Column(name = "updated_by")
	private Long updatedBy;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected PlatformSetting() {
		// Required by JPA.
	}

	/**
	 * Applies a new value after checking it against this setting's own bounds.
	 *
	 * @throws IllegalArgumentException if it is not a number of the right type, or falls outside
	 *     the range — rejected before it is stored, so a bad value never reaches the code that
	 *     depends on it
	 */
	public void update(String newValue, Long adminUserId) {
		BigDecimal parsed;
		try {
			parsed = new BigDecimal(newValue.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("'%s' is not a number".formatted(newValue));
		}

		if ("INT".equals(valueType) && parsed.stripTrailingZeros().scale() > 0) {
			throw new IllegalArgumentException("%s must be a whole number".formatted(key));
		}
		if (minValue != null && parsed.compareTo(minValue) < 0) {
			throw new IllegalArgumentException(
					"%s must be at least %s".formatted(key, minValue.stripTrailingZeros().toPlainString()));
		}
		if (maxValue != null && parsed.compareTo(maxValue) > 0) {
			throw new IllegalArgumentException(
					"%s must be at most %s".formatted(key, maxValue.stripTrailingZeros().toPlainString()));
		}

		this.value = parsed.stripTrailingZeros().toPlainString();
		this.updatedBy = adminUserId;
	}

	public int asInt() {
		return new BigDecimal(value).intValue();
	}

	public BigDecimal asDecimal() {
		return new BigDecimal(value);
	}

	public String getKey() {
		return key;
	}

	public String getValue() {
		return value;
	}

	public String getValueType() {
		return valueType;
	}

	public String getDescription() {
		return description;
	}

	public BigDecimal getMinValue() {
		return minValue;
	}

	public BigDecimal getMaxValue() {
		return maxValue;
	}

	public Long getUpdatedBy() {
		return updatedBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
