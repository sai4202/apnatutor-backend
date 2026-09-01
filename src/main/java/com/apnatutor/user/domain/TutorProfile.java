package com.apnatutor.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tutor's profile — the core inventory of the marketplace.
 *
 * <p>Search, the lead feed and every unlock depend on these existing, which is why this is the
 * first thing built after authentication.
 *
 * <p>Fees are stored in paise as {@code Long}, never a floating-point type (SOURCE_OF_TRUTH.md §5).
 * Aggregates ({@code avgRating}, {@code reviewCount}, {@code profileCompleteness}) are derived and
 * recomputed server-side; a client-supplied rating would be trivially forged.
 */
@Entity
@Table(name = "tutor_profiles")
public class TutorProfile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, unique = true)
	private Long userId;

	@Column(name = "display_name", length = 120)
	private String displayName;

	@Column(length = 160)
	private String headline;

	@Column(columnDefinition = "text")
	private String bio;

	@Column(name = "photo_url", length = 500)
	private String photoUrl;

	@Enumerated(EnumType.STRING)
	@Column(length = 16)
	private Gender gender;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	@Column(name = "experience_years", nullable = false)
	private int experienceYears;

	@Column(name = "fee_min_paise")
	private Long feeMinPaise;

	@Column(name = "fee_max_paise")
	private Long feeMaxPaise;

	@Enumerated(EnumType.STRING)
	@Column(name = "fee_unit", length = 16)
	private FeeUnit feeUnit;

	@Column(name = "fee_negotiable", nullable = false)
	private boolean feeNegotiable;

	// Mapped as a Postgres array rather than a join table: a fixed set of at most three values,
	// always read with the profile and never queried on its own.
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "teaching_modes", columnDefinition = "varchar(16)[]")
	private String[] teachingModes = new String[0];

	@Column(name = "travel_radius_km", nullable = false)
	private int travelRadiusKm;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(columnDefinition = "varchar(60)[]")
	private String[] languages = new String[0];

	@Column(name = "offers_demo", nullable = false)
	private boolean offersDemo;

	@Column(name = "availability_note", length = 400)
	private String availabilityNote;

	@Column(name = "profile_completeness", nullable = false)
	private int profileCompleteness;

	@Column(name = "avg_rating")
	private java.math.BigDecimal avgRating;

	@Column(name = "review_count", nullable = false)
	private int reviewCount;

	@Column(name = "response_rate")
	private Integer responseRate;

	@Column(name = "is_published", nullable = false)
	private boolean published;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	/**
	 * Owned collections, cascaded and orphan-removed: a subject or location has no meaning apart
	 * from its tutor, and deleting the profile must take them with it.
	 *
	 * <p>Lazy because the search projection never needs them — loading every tutor's full subject
	 * list to render a results page would be exactly the N+1 that makes search slow.
	 */
	@OneToMany(mappedBy = "tutor", cascade = CascadeType.ALL, orphanRemoval = true,
			fetch = FetchType.LAZY)
	private List<TutorSubject> subjects = new ArrayList<>();

	@OneToMany(mappedBy = "tutor", cascade = CascadeType.ALL, orphanRemoval = true,
			fetch = FetchType.LAZY)
	private List<TutorLocation> locations = new ArrayList<>();

	@OneToMany(mappedBy = "tutor", cascade = CascadeType.ALL, orphanRemoval = true,
			fetch = FetchType.LAZY)
	private List<TutorQualification> qualifications = new ArrayList<>();

	protected TutorProfile() {
		// Required by JPA.
	}

	public static TutorProfile createFor(Long userId) {
		TutorProfile profile = new TutorProfile();
		profile.userId = userId;
		return profile;
	}

	/**
	 * Publishes the profile so it can appear in search.
	 *
	 * <p>Refuses while the profile is incomplete. An empty profile in results wastes a parent's
	 * time and reflects on every other tutor on the platform, so the check lives here on the entity
	 * rather than in a controller that could be bypassed by a second caller.
	 */
	public void publish(Instant at, int minimumCompleteness) {
		if (profileCompleteness < minimumCompleteness) {
			throw new IllegalStateException(
					"Profile is %d%% complete; %d%% is required to publish"
							.formatted(profileCompleteness, minimumCompleteness));
		}
		this.published = true;
		this.publishedAt = at;
	}

	public void unpublish() {
		this.published = false;
	}

	// --- Mutators used by the service layer ---------------------------------------------------

	public void updateBasics(
			String displayName,
			String headline,
			String bio,
			Gender gender,
			LocalDate dateOfBirth,
			int experienceYears,
			String[] languages,
			boolean offersDemo,
			String availabilityNote) {
		this.displayName = displayName;
		this.headline = headline;
		this.bio = bio;
		this.gender = gender;
		this.dateOfBirth = dateOfBirth;
		this.experienceYears = experienceYears;
		this.languages = languages == null ? new String[0] : languages;
		this.offersDemo = offersDemo;
		this.availabilityNote = availabilityNote;
	}

	public void updateFees(Long feeMinPaise, Long feeMaxPaise, FeeUnit feeUnit, boolean negotiable) {
		this.feeMinPaise = feeMinPaise;
		this.feeMaxPaise = feeMaxPaise;
		this.feeUnit = feeUnit;
		this.feeNegotiable = negotiable;
	}

	public void updateTeaching(String[] teachingModes, int travelRadiusKm) {
		this.teachingModes = teachingModes == null ? new String[0] : teachingModes;
		this.travelRadiusKm = travelRadiusKm;
	}

	public void setPhotoUrl(String photoUrl) {
		this.photoUrl = photoUrl;
	}

	public void setProfileCompleteness(int profileCompleteness) {
		this.profileCompleteness = profileCompleteness;
	}

	// --- Accessors ------------------------------------------------------------------------------

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getHeadline() {
		return headline;
	}

	public String getBio() {
		return bio;
	}

	public String getPhotoUrl() {
		return photoUrl;
	}

	public Gender getGender() {
		return gender;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public int getExperienceYears() {
		return experienceYears;
	}

	public Long getFeeMinPaise() {
		return feeMinPaise;
	}

	public Long getFeeMaxPaise() {
		return feeMaxPaise;
	}

	public FeeUnit getFeeUnit() {
		return feeUnit;
	}

	public boolean isFeeNegotiable() {
		return feeNegotiable;
	}

	public String[] getTeachingModes() {
		return teachingModes;
	}

	public int getTravelRadiusKm() {
		return travelRadiusKm;
	}

	public String[] getLanguages() {
		return languages;
	}

	public boolean isOffersDemo() {
		return offersDemo;
	}

	public String getAvailabilityNote() {
		return availabilityNote;
	}

	public int getProfileCompleteness() {
		return profileCompleteness;
	}

	public java.math.BigDecimal getAvgRating() {
		return avgRating;
	}

	public int getReviewCount() {
		return reviewCount;
	}

	public Integer getResponseRate() {
		return responseRate;
	}

	public boolean isPublished() {
		return published;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public List<TutorSubject> getSubjects() {
		return subjects;
	}

	public List<TutorLocation> getLocations() {
		return locations;
	}

	public List<TutorQualification> getQualifications() {
		return qualifications;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
