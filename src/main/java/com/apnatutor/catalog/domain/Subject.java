package com.apnatutor.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A node in the subject tree: {@code School Tuition → Mathematics}.
 *
 * <p>The parent link is stored as a plain id rather than a JPA association. The tree is small,
 * read-constantly and never traversed lazily per-row; loading it whole and assembling it in memory
 * avoids the N+1 queries that a mapped {@code @OneToMany} invites here.
 *
 * <p>{@code slug} becomes a public URL segment and is effectively permanent once a search engine
 * has indexed it — changing one later is a redirect, not an edit.
 */
@Entity
@Table(name = "subjects")
public class Subject {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "parent_id")
	private Long parentId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, unique = true, length = 140)
	private String slug;

	@Column(name = "is_leaf", nullable = false)
	private boolean leaf;

	@Column(name = "display_order", nullable = false)
	private int displayOrder;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	protected Subject() {
		// Required by JPA.
	}

	public Long getId() {
		return id;
	}

	public Long getParentId() {
		return parentId;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public boolean isLeaf() {
		return leaf;
	}

	public int getDisplayOrder() {
		return displayOrder;
	}

	public boolean isActive() {
		return active;
	}
}
