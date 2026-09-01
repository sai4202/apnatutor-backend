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

/**
 * A degree or certification a tutor claims.
 *
 * <p>{@code documentUrl} points at an uploaded certificate and is <strong>admin-only</strong>. It
 * must never appear in a public DTO: these are degree certificates and identity documents, and
 * serving one publicly is a data-protection incident rather than a feature. The public profile
 * shows {@code isVerified} instead — which is the only part a parent actually needs.
 */
@Entity
@Table(name = "tutor_qualifications")
public class TutorQualification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "tutor_id", nullable = false)
	private TutorProfile tutor;

	@Column(nullable = false, length = 160)
	private String degree;

	@Column(nullable = false, length = 200)
	private String institution;

	private Integer year;

	@Column(name = "document_url", length = 500)
	private String documentUrl;

	@Column(name = "is_verified", nullable = false)
	private boolean verified;

	protected TutorQualification() {
		// Required by JPA.
	}

	public TutorQualification(
			TutorProfile tutor, String degree, String institution, Integer year) {
		this.tutor = tutor;
		this.degree = degree;
		this.institution = institution;
		this.year = year;
	}

	public void attachDocument(String documentUrl) {
		this.documentUrl = documentUrl;
		// A new document invalidates any previous approval — otherwise a tutor could get a blank
		// page approved and then swap in whatever they liked.
		this.verified = false;
	}

	public void markVerified() {
		this.verified = true;
	}

	public Long getId() {
		return id;
	}

	public TutorProfile getTutor() {
		return tutor;
	}

	public String getDegree() {
		return degree;
	}

	public String getInstitution() {
		return institution;
	}

	public Integer getYear() {
		return year;
	}

	public String getDocumentUrl() {
		return documentUrl;
	}

	public boolean isVerified() {
		return verified;
	}
}
