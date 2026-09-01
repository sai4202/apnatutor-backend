package com.apnatutor.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An education board: CBSE, ICSE, a state board, IB.
 *
 * <p>A first-class filter rather than a free-text field, because in India a parent looking for a
 * CBSE tutor genuinely does not want a State Board one — the syllabus differs.
 */
@Entity
@Table(name = "boards")
public class Board {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, unique = true, length = 140)
	private String slug;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Board() {
		// Required by JPA.
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public boolean isActive() {
		return active;
	}
}
