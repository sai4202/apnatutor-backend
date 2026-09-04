package com.apnatutor.user;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.apnatutor.catalog.BoardRepository;
import com.apnatutor.catalog.GradeLevelRepository;
import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.domain.Board;
import com.apnatutor.catalog.domain.GradeLevel;
import com.apnatutor.catalog.domain.Location;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.common.exception.ApiException;
import com.apnatutor.common.web.ErrorCode;
import com.apnatutor.storage.FileKind;
import com.apnatutor.storage.FileStorage;
import com.apnatutor.verification.VerificationService;
import com.apnatutor.verification.domain.VerificationType;
import com.apnatutor.user.domain.TutorLocation;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.TutorQualification;
import com.apnatutor.user.domain.TutorSubject;
import com.apnatutor.user.dto.TutorProfileDtos.LocationView;
import com.apnatutor.user.dto.TutorProfileDtos.OwnerView;
import com.apnatutor.user.dto.TutorProfileDtos.PublicView;
import com.apnatutor.user.dto.TutorProfileDtos.QualificationRequest;
import com.apnatutor.user.dto.TutorProfileDtos.QualificationView;
import com.apnatutor.user.dto.TutorProfileDtos.SubjectView;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateBasicsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateFeesRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateSubjectsRequest;
import com.apnatutor.user.dto.TutorProfileDtos.UpdateTeachingRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tutor profile management.
 *
 * <p>Every mutating method recomputes {@link ProfileCompleteness} before returning, so the score
 * can never drift from the data it describes. Doing it in one place rather than at each call site
 * is what stops a new endpoint quietly forgetting.
 *
 * <p>Catalog references are validated against the database. Accepting an arbitrary {@code
 * subjectId} would let a tutor attach themselves to a subject that does not exist, which surfaces
 * later as a broken search result rather than an error at the point of the mistake.
 */
@Service
public class TutorProfileService {

	private final TutorProfileRepository profiles;
	private final SubjectRepository subjects;
	private final LocationRepository locations;
	private final GradeLevelRepository gradeLevels;
	private final BoardRepository boards;
	private final FileStorage fileStorage;
	private final VerificationService verificationService;
	private final Clock clock;

	public TutorProfileService(
			TutorProfileRepository profiles,
			SubjectRepository subjects,
			LocationRepository locations,
			GradeLevelRepository gradeLevels,
			BoardRepository boards,
			FileStorage fileStorage,
			VerificationService verificationService,
			Clock clock) {
		this.profiles = profiles;
		this.subjects = subjects;
		this.locations = locations;
		this.gradeLevels = gradeLevels;
		this.boards = boards;
		this.fileStorage = fileStorage;
		this.verificationService = verificationService;
		this.clock = clock;
	}

	/**
	 * The caller's profile, created on first access.
	 *
	 * <p>Created lazily rather than at registration so signing up stays a single OTP step. The
	 * tutor meets the profile form when they choose to, not as a wall immediately after verifying
	 * their phone.
	 */
	@Transactional
	public TutorProfile getOrCreate(Long userId) {
		return profiles.findByUserId(userId)
				.orElseGet(() -> profiles.save(TutorProfile.createFor(userId)));
	}

	@Transactional
	public OwnerView updateBasics(Long userId, UpdateBasicsRequest request) {
		TutorProfile profile = getOrCreate(userId);
		profile.updateBasics(
				request.displayName(),
				request.headline(),
				request.bio(),
				request.gender(),
				request.dateOfBirth(),
				request.experienceYearsOrZero(),
				request.languages() == null ? new String[0] : request.languages().toArray(String[]::new),
				request.offersDemoOrFalse(),
				request.availabilityNote());
		return saveAndView(profile);
	}

	@Transactional
	public OwnerView updateFees(Long userId, UpdateFeesRequest request) {
		TutorProfile profile = getOrCreate(userId);
		profile.updateFees(
				request.feeMinPaise(),
				request.feeMaxPaise(),
				request.feeUnit(),
				request.negotiableOrFalse());
		return saveAndView(profile);
	}

	@Transactional
	public OwnerView updateTeaching(Long userId, UpdateTeachingRequest request) {
		TutorProfile profile = getOrCreate(userId);

		for (String mode : request.teachingModes()) {
			if (!List.of("STUDENT_HOME", "TUTOR_PLACE", "ONLINE").contains(mode)) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown teaching mode: " + mode);
			}
		}

		profile.updateTeaching(
				request.teachingModes().toArray(String[]::new), request.travelRadiusOrZero());

		// Replace rather than merge. A tutor removing a locality expects it gone, and orphanRemoval
		// on the collection turns clear-and-refill into the right DELETEs.
		profile.getLocations().clear();
		for (Long locationId : request.locationIds() == null ? List.<Long>of() : request.locationIds()) {
			locations.findById(locationId).orElseThrow(
					() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown location: " + locationId));
			profile.getLocations().add(new TutorLocation(profile, locationId));
		}

		return saveAndView(profile);
	}

	@Transactional
	public OwnerView updateSubjects(Long userId, UpdateSubjectsRequest request) {
		TutorProfile profile = getOrCreate(userId);

		profile.getSubjects().clear();
		for (var subjectRequest : request.subjects()) {
			Subject subject = subjects.findById(subjectRequest.subjectId())
					.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
							"Unknown subject: " + subjectRequest.subjectId()));

			// Only leaves are teachable. "School Tuition" is a navigation category, not something
			// a tutor can claim to teach, and allowing it would put them in every search.
			if (!subject.isLeaf()) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"'%s' is a category. Choose a specific subject within it."
								.formatted(subject.getName()));
			}

			profile.getSubjects().add(new TutorSubject(
					profile,
					subject.getId(),
					subjectRequest.feePaise(),
					subjectRequest.feeUnit(),
					toArray(subjectRequest.gradeLevelIds()),
					toArray(subjectRequest.boardIds())));
		}

		return saveAndView(profile);
	}

	/**
	 * Replaces the tutor's profile photo.
	 *
	 * <p>The old file is deleted after the new key is persisted, not before. If the write fails the
	 * tutor keeps the photo they had, rather than ending up with a profile pointing at a file that
	 * no longer exists.
	 */
	@Transactional
	public OwnerView updatePhoto(Long userId, byte[] content) {
		TutorProfile profile = getOrCreate(userId);
		String previousKey = profile.getPhotoUrl();

		FileStorage.StoredFile stored = fileStorage.store(content, FileKind.PROFILE_PHOTO);
		profile.setPhotoUrl(stored.storageKey());
		OwnerView view = saveAndView(profile);

		if (previousKey != null && !previousKey.isBlank()) {
			fileStorage.delete(previousKey);
		}
		return view;
	}

	/**
	 * Attaches a supporting document to a qualification.
	 *
	 * <p>Uploading a new document clears any previous approval — see {@code
	 * TutorQualification.attachDocument}. Without that, a tutor could get a blank page approved and
	 * then swap in whatever they liked.
	 */
	@Transactional
	public OwnerView attachQualificationDocument(
			Long userId, Long qualificationId, byte[] content) {
		TutorProfile profile = getOrCreate(userId);

		TutorQualification qualification = profile.getQualifications().stream()
				.filter(q -> q.getId().equals(qualificationId))
				.findFirst()
				// Scoped to this tutor's own qualifications, so one tutor cannot attach a document
				// to another's by guessing an id.
				.orElseThrow(() -> ApiException.notFound("Qualification"));

		String previousKey = qualification.getDocumentUrl();
		FileStorage.StoredFile stored =
				fileStorage.store(content, FileKind.EDUCATION_DOCUMENT);
		qualification.attachDocument(stored.storageKey());

		OwnerView view = saveAndView(profile);
		if (previousKey != null && !previousKey.isBlank()) {
			fileStorage.delete(previousKey);
		}
		return view;
	}

	@Transactional
	public OwnerView addQualification(Long userId, QualificationRequest request) {
		TutorProfile profile = getOrCreate(userId);
		profile.getQualifications().add(new TutorQualification(
				profile, request.degree(), request.institution(), request.year()));
		return saveAndView(profile);
	}

	@Transactional
	public OwnerView removeQualification(Long userId, Long qualificationId) {
		TutorProfile profile = getOrCreate(userId);
		boolean removed = profile.getQualifications()
				.removeIf(q -> q.getId().equals(qualificationId));
		if (!removed) {
			throw ApiException.notFound("Qualification");
		}
		return saveAndView(profile);
	}

	/**
	 * Makes the profile visible in search.
	 *
	 * @throws ApiException with {@link ErrorCode#PROFILE_INCOMPLETE} if it is not ready
	 */
	@Transactional
	public OwnerView publish(Long userId) {
		TutorProfile profile = getOrCreate(userId);
		profile.setProfileCompleteness(ProfileCompleteness.score(profile));

		if (profile.getProfileCompleteness() < ProfileCompleteness.MINIMUM_TO_PUBLISH) {
			throw new ApiException(ErrorCode.PROFILE_INCOMPLETE,
					"Your profile is %d%% complete. It needs %d%% before students can find it."
							.formatted(profile.getProfileCompleteness(),
									ProfileCompleteness.MINIMUM_TO_PUBLISH));
		}

		profile.publish(clock.instant(), ProfileCompleteness.MINIMUM_TO_PUBLISH);
		return toOwnerView(profiles.save(profile));
	}

	@Transactional
	public OwnerView unpublish(Long userId) {
		TutorProfile profile = getOrCreate(userId);
		profile.unpublish();
		return saveAndView(profile);
	}

	/**
	 * Not {@code readOnly}, despite reading: {@link #getOrCreate} inserts on first access, and a
	 * read-only transaction cannot write. Marking this read-only made the very first profile fetch
	 * fail with a 500.
	 */
	@Transactional
	public OwnerView getOwnProfile(Long userId) {
		return toOwnerView(getOrCreate(userId));
	}

	/**
	 * The public projection. Only published profiles owned by an account in good standing.
	 *
	 * <p>The suspension check is in the query rather than a {@code filter} here — see
	 * {@link TutorProfileRepository#findPublishedActiveById}.
	 */
	@Transactional(readOnly = true)
	public PublicView getPublicProfile(Long profileId) {
		// The collections load lazily inside this read-only transaction; see the note on
		// TutorProfileRepository for why they are not join-fetched.
		TutorProfile profile = profiles.findPublishedActiveById(profileId)
				.orElseThrow(() -> ApiException.notFound("Tutor"));
		return toPublicView(profile);
	}

	// --- Internals ------------------------------------------------------------------------------

	private OwnerView saveAndView(TutorProfile profile) {
		profile.setProfileCompleteness(ProfileCompleteness.score(profile));

		// An edit that drops a profile below the bar unpublishes it. Leaving it live and empty
		// would put a broken listing in front of parents.
		if (profile.isPublished()
				&& profile.getProfileCompleteness() < ProfileCompleteness.MINIMUM_TO_PUBLISH) {
			profile.unpublish();
		}

		return toOwnerView(profiles.save(profile));
	}

	private static Long[] toArray(List<Long> values) {
		return values == null ? new Long[0] : values.toArray(Long[]::new);
	}

	/**
	 * Names for the catalog ids on a profile, fetched in bulk.
	 *
	 * <p>Three queries regardless of how many subjects a tutor lists, rather than one per id.
	 */
	private CatalogNames catalogNames(TutorProfile profile) {
		Map<Long, Subject> subjectsById = subjects.findAll().stream()
				.collect(Collectors.toMap(Subject::getId, Function.identity()));
		Map<Long, GradeLevel> gradesById = gradeLevels.findAll().stream()
				.collect(Collectors.toMap(GradeLevel::getId, Function.identity()));
		Map<Long, Board> boardsById = boards.findAll().stream()
				.collect(Collectors.toMap(Board::getId, Function.identity()));
		Map<Long, Location> locationsById = locations.findAll().stream()
				.collect(Collectors.toMap(Location::getId, Function.identity()));
		return new CatalogNames(subjectsById, gradesById, boardsById, locationsById);
	}

	private record CatalogNames(
			Map<Long, Subject> subjects,
			Map<Long, GradeLevel> grades,
			Map<Long, Board> boards,
			Map<Long, Location> locations) {
	}

	private List<SubjectView> subjectViews(TutorProfile profile, CatalogNames names) {
		List<SubjectView> views = new ArrayList<>();
		for (TutorSubject tutorSubject : profile.getSubjects()) {
			Subject subject = names.subjects().get(tutorSubject.getSubjectId());
			views.add(new SubjectView(
					tutorSubject.getSubjectId(),
					subject == null ? "Unknown" : subject.getName(),
					subject == null ? null : subject.getSlug(),
					tutorSubject.getFeePaise(),
					tutorSubject.getFeeUnit(),
					List.of(tutorSubject.getGradeLevelIds()).stream()
							.map(names.grades()::get)
							.filter(java.util.Objects::nonNull)
							.map(GradeLevel::getName)
							.toList(),
					List.of(tutorSubject.getBoardIds()).stream()
							.map(names.boards()::get)
							.filter(java.util.Objects::nonNull)
							.map(Board::getName)
							.toList()));
		}
		return views;
	}

	private List<LocationView> locationViews(TutorProfile profile, CatalogNames names) {
		return profile.getLocations().stream()
				.map(tutorLocation -> {
					Location location = names.locations().get(tutorLocation.getLocationId());
					return new LocationView(
							tutorLocation.getLocationId(),
							location == null ? "Unknown" : location.displayName(),
							location == null ? null : location.getSlug());
				})
				.toList();
	}

	private static List<QualificationView> qualificationViews(TutorProfile profile) {
		// documentUrl is deliberately not mapped. It points at a degree certificate or identity
		// document and is admin-only; the public answer is the verified flag.
		return profile.getQualifications().stream()
				.map(q -> new QualificationView(
						q.getId(), q.getDegree(), q.getInstitution(), q.getYear(), q.isVerified()))
				.toList();
	}

	private OwnerView toOwnerView(TutorProfile profile) {
		CatalogNames names = catalogNames(profile);
		return new OwnerView(
				profile.getId(),
				profile.getDisplayName(),
				profile.getHeadline(),
				profile.getBio(),
				profile.getPhotoUrl(),
				profile.getGender(),
				profile.getDateOfBirth(),
				profile.getExperienceYears(),
				profile.getFeeMinPaise(),
				profile.getFeeMaxPaise(),
				profile.getFeeUnit(),
				profile.isFeeNegotiable(),
				List.of(profile.getTeachingModes()),
				profile.getTravelRadiusKm(),
				List.of(profile.getLanguages()),
				profile.isOffersDemo(),
				profile.getAvailabilityNote(),
				subjectViews(profile, names),
				locationViews(profile, names),
				qualificationViews(profile),
				profile.getProfileCompleteness(),
				profile.isPublished(),
				missingForPublish(profile));
	}

	private PublicView toPublicView(TutorProfile profile) {
		CatalogNames names = catalogNames(profile);
		return new PublicView(
				profile.getId(),
				profile.getDisplayName(),
				profile.getHeadline(),
				profile.getBio(),
				profile.getPhotoUrl(),
				profile.getGender(),
				profile.getExperienceYears(),
				profile.getFeeMinPaise(),
				profile.getFeeMaxPaise(),
				profile.getFeeUnit(),
				profile.isFeeNegotiable(),
				List.of(profile.getTeachingModes()),
				List.of(profile.getLanguages()),
				profile.isOffersDemo(),
				profile.getAvailabilityNote(),
				subjectViews(profile, names),
				locationViews(profile, names),
				qualificationViews(profile),
				profile.getAvgRating(),
				profile.getReviewCount(),
				verificationService.levelFor(profile.getUserId()),
				badgesFor(profile.getUserId()));
	}

	/**
	 * The verification badges a parent sees.
	 *
	 * <p>Strings rather than the enum, because this is display copy — "ID verified" is what a parent
	 * reads, not {@code ID}. The underlying level is returned alongside for anything that needs to
	 * branch.
	 */
	private List<String> badgesFor(Long userId) {
		List<String> badges = new ArrayList<>();
		badges.add("Phone verified");
		for (VerificationType type : verificationService.approvedTypes(userId)) {
			badges.add(switch (type) {
				case ID -> "ID verified";
				case EDUCATION -> "Qualifications verified";
				case EMAIL -> "Email verified";
			});
		}
		return badges;
	}

	/**
	 * What is still missing, in plain words.
	 *
	 * <p>A bare "60% complete" tells a tutor they are stuck without telling them what to do. This is
	 * the difference between an abandoned profile and a finished one.
	 */
	private static List<String> missingForPublish(TutorProfile profile) {
		List<String> missing = new ArrayList<>();
		if (profile.getSubjects().isEmpty()) {
			missing.add("Add at least one subject you teach");
		}
		if (profile.getTeachingModes().length == 0) {
			missing.add("Choose how you teach — at the student's home, your place, or online");
		}
		if (profile.getLocations().isEmpty()
				&& !List.of(profile.getTeachingModes()).contains("ONLINE")) {
			missing.add("Add the areas you can travel to");
		}
		if (profile.getFeeMinPaise() == null || profile.getFeeUnit() == null) {
			missing.add("Set your fees");
		}
		if (profile.getHeadline() == null || profile.getHeadline().isBlank()) {
			missing.add("Write a short headline, e.g. \"Maths & Science, Classes 6-10\"");
		}
		if (profile.getBio() == null || profile.getBio().trim().length() < 80) {
			missing.add("Write a few lines about how you teach (at least 80 characters)");
		}
		if (profile.getPhotoUrl() == null || profile.getPhotoUrl().isBlank()) {
			missing.add("Add a photo — parents are far more likely to contact a tutor they can see");
		}
		return missing;
	}
}
