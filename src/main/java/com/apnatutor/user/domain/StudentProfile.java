package com.apnatutor.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A student or parent's profile.
 *
 * <p>Deliberately thin — a name and a location, both optional. Every field asked for before someone
 * can post a requirement is a chance for them to abandon the funnel, and none of this is needed to
 * match them with a tutor: the requirement itself carries the subject, budget and area.
 *
 * <p>Contrast with {@link TutorProfile}, which is elaborate because a tutor profile is the product
 * being browsed. A student profile is not browsed by anyone.
 */
@Entity
@Table(name = "student_profiles")
public class StudentProfile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, unique = true)
	private Long userId;

	@Column(length = 120)
	private String name;

	@Column(name = "location_id")
	private Long locationId;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected StudentProfile() {
		// Required by JPA.
	}

	public static StudentProfile createFor(Long userId) {
		StudentProfile profile = new StudentProfile();
		profile.userId = userId;
		return profile;
	}

	public void update(String name, Long locationId) {
		this.name = name;
		this.locationId = locationId;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public Long getLocationId() {
		return locationId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
