package com.apnatutor.catalog.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A city or a locality within one.
 *
 * <p>Flat by design: a city row has a null locality, a locality row names its city. Two levels is
 * all this product needs, and a flat table keeps the tutor-search query simple — which matters,
 * because that is the hottest query in the application.
 *
 * <p>Coordinates are nullable. A locality can be listed before anyone geocodes it, and a missing
 * coordinate must never block a tutor from signing up.
 */
@Entity
@Table(name = "locations")
public class Location {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String state;

	@Column(nullable = false, length = 120)
	private String city;

	/** Null when this row represents the city itself. */
	@Column(length = 120)
	private String locality;

	@Column(nullable = false, unique = true, length = 200)
	private String slug;

	@Column(precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(precision = 9, scale = 6)
	private BigDecimal longitude;

	/**
	 * True when this row is the city itself rather than a locality within it.
	 *
	 * <p>Named {@code cityLevel} rather than something containing an underscore because Spring Data
	 * treats {@code _} in a derived query method as a property-path separator — a field named
	 * {@code city_} would make {@code findByCity_True} parse as {@code city.true} and fail at
	 * startup.
	 */
	@Column(name = "is_city", nullable = false)
	private boolean cityLevel;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Location() {
		// Required by JPA.
	}

	/** Human-readable form: "Gachibowli, Hyderabad" or just "Hyderabad". */
	public String displayName() {
		return locality == null ? city : locality + ", " + city;
	}

	public Long getId() {
		return id;
	}

	public String getState() {
		return state;
	}

	public String getCity() {
		return city;
	}

	public String getLocality() {
		return locality;
	}

	public String getSlug() {
		return slug;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

	public boolean isCityLevel() {
		return cityLevel;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public boolean isActive() {
		return active;
	}
}
