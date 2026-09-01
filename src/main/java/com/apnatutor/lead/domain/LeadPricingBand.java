package com.apnatutor.lead.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One rung of the lead pricing ladder.
 *
 * <p>A row rather than a constant, so an admin can reprice without a deploy. Each band is an
 * inclusive lower bound; a budget is priced by the highest band it meets.
 */
@Entity
@Table(name = "lead_pricing_bands")
public class LeadPricingBand {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "min_budget_paise", nullable = false)
	private long minBudgetPaise;

	@Column(nullable = false)
	private int credits;

	@Column(nullable = false, length = 60)
	private String label;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected LeadPricingBand() {
		// Required by JPA.
	}

	public LeadPricingBand(long minBudgetPaise, int credits, String label) {
		this.minBudgetPaise = minBudgetPaise;
		this.credits = credits;
		this.label = label;
	}

	public void update(int credits, String label) {
		if (credits <= 0) {
			// A zero-credit band is a free contact reveal — the business model given away by a
			// configuration change. The database has the same constraint.
			throw new IllegalArgumentException("A band must cost at least 1 credit");
		}
		this.credits = credits;
		this.label = label;
	}

	public Long getId() {
		return id;
	}

	public long getMinBudgetPaise() {
		return minBudgetPaise;
	}

	public int getCredits() {
		return credits;
	}

	public String getLabel() {
		return label;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
