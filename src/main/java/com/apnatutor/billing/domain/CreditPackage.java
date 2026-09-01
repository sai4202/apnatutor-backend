package com.apnatutor.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A bundle of credits at a price.
 *
 * <p>A row rather than a constant, for the same reason the pricing bands are rows: the prices are a
 * hypothesis (PENDING.md D3), and a repricing that needs a release is a repricing that does not
 * happen.
 *
 * <p>Retired rather than deleted — {@link #isActive()} hides a package from the storefront while
 * leaving every historical payment able to resolve what it bought.
 */
@Entity
@Table(name = "credit_packages")
public class CreditPackage {

	/** Guards against a fat-fingered price that would give the platform away. */
	private static final long MAX_PRICE_PAISE = 100_000_00L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(nullable = false)
	private int credits;

	@Column(name = "price_paise", nullable = false)
	private long pricePaise;

	@Column(nullable = false)
	private boolean highlighted;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected CreditPackage() {
		// Required by JPA.
	}

	public static CreditPackage of(
			String name, int credits, long pricePaise, boolean highlighted, int sortOrder) {
		CreditPackage created = new CreditPackage();
		created.name = name;
		created.applyPricing(credits, pricePaise);
		created.highlighted = highlighted;
		created.sortOrder = sortOrder;
		created.active = true;
		return created;
	}

	/**
	 * Repricing.
	 *
	 * <p>Only ever affects future purchases: {@code payments} copies the credits and amount at order
	 * time rather than reading them back through the foreign key, so what someone paid last month
	 * cannot be rewritten by an edit today.
	 */
	public void update(
			String name, int credits, long pricePaise, boolean highlighted, int sortOrder) {
		this.name = name;
		applyPricing(credits, pricePaise);
		this.highlighted = highlighted;
		this.sortOrder = sortOrder;
	}

	private void applyPricing(int credits, long pricePaise) {
		if (credits <= 0) {
			throw new IllegalArgumentException("A package must contain at least one credit");
		}
		// A zero-price package is free credits behind a checkout button — the business model given
		// away by a configuration change, which is exactly the failure the bounds exist to prevent.
		if (pricePaise <= 0) {
			throw new IllegalArgumentException("A package must cost something");
		}
		if (pricePaise > MAX_PRICE_PAISE) {
			throw new IllegalArgumentException(
					"A package cannot cost more than Rs " + (MAX_PRICE_PAISE / 100));
		}
		this.credits = credits;
		this.pricePaise = pricePaise;
	}

	/** Retires a package without deleting it, so historical payments still resolve. */
	public void retire() {
		this.active = false;
	}

	public void restore() {
		this.active = true;
	}

	/** Paise per credit, for the "save 25%" line on the storefront. */
	public long pricePerCreditPaise() {
		return pricePaise / credits;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public int getCredits() {
		return credits;
	}

	public long getPricePaise() {
		return pricePaise;
	}

	public boolean isHighlighted() {
		return highlighted;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
