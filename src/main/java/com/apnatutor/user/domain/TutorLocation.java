package com.apnatutor.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A locality a tutor is willing to travel to. */
@Entity
@Table(name = "tutor_locations")
public class TutorLocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "tutor_id", nullable = false)
	private TutorProfile tutor;

	@Column(name = "location_id", nullable = false)
	private Long locationId;

	protected TutorLocation() {
		// Required by JPA.
	}

	public TutorLocation(TutorProfile tutor, Long locationId) {
		this.tutor = tutor;
		this.locationId = locationId;
	}

	public Long getId() {
		return id;
	}

	public TutorProfile getTutor() {
		return tutor;
	}

	public Long getLocationId() {
		return locationId;
	}
}
