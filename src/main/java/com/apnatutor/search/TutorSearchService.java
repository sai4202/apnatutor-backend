package com.apnatutor.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.apnatutor.catalog.LocationRepository;
import com.apnatutor.catalog.SubjectRepository;
import com.apnatutor.catalog.domain.Location;
import com.apnatutor.catalog.domain.Subject;
import com.apnatutor.common.web.PageResponse;
import com.apnatutor.search.dto.SearchDtos.SearchFacets;
import com.apnatutor.search.dto.SearchDtos.TutorSearchQuery;
import com.apnatutor.search.dto.SearchDtos.TutorSearchResult;
import com.apnatutor.user.TutorProfileRepository;
import com.apnatutor.user.domain.TutorLocation;
import com.apnatutor.user.domain.TutorProfile;
import com.apnatutor.user.domain.TutorSubject;
import com.apnatutor.verification.VerificationRepository;
import com.apnatutor.verification.domain.VerificationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tutor search.
 *
 * <p>Results are assembled from a flat projection rather than loaded as entities. A results page
 * needs a dozen columns from each tutor; loading full entities would drag in every subject,
 * location and qualification for every row — the N+1 that makes search slow at exactly the moment
 * it matters.
 *
 * <p>Subject and locality names are then filled in with two bulk queries, not one per tutor.
 */
@Service
public class TutorSearchService {

	/** Guards against a caller asking for the whole table in one request. */
	private static final int MAX_PAGE_SIZE = 50;

	private final TutorSearchRepository searchRepository;
	private final TutorProfileRepository profiles;
	private final SubjectRepository subjects;
	private final LocationRepository locations;
	private final VerificationRepository verifications;

	public TutorSearchService(
			TutorSearchRepository searchRepository,
			TutorProfileRepository profiles,
			SubjectRepository subjects,
			LocationRepository locations,
			VerificationRepository verifications) {
		this.searchRepository = searchRepository;
		this.profiles = profiles;
		this.subjects = subjects;
		this.locations = locations;
		this.verifications = verifications;
	}

	@Transactional(readOnly = true)
	public PageResponse<TutorSearchResult> search(TutorSearchQuery query, int page, int size) {
		int pageNumber = Math.max(page, 0);
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

		long total = searchRepository.count(query);
		List<Object[]> rows = searchRepository.search(query, pageNumber * pageSize, pageSize);

		List<TutorSearchResult> results = enrich(rows);

		return new PageResponse<>(
				results,
				pageNumber,
				pageSize,
				total,
				(int) Math.ceil((double) total / pageSize));
	}

	@Transactional(readOnly = true)
	public SearchFacets facets(TutorSearchQuery query) {
		return new SearchFacets(
				searchRepository.count(query),
				searchRepository.countMatching(query, "'ONLINE' = ANY(tp.teaching_modes)"),
				searchRepository.countMatching(query, """
						EXISTS (SELECT 1 FROM verifications v
						        WHERE v.user_id = tp.user_id
						          AND v.type = 'ID' AND v.status = 'APPROVED')"""));
	}

	/**
	 * Turns raw rows into results, filling in subject and locality names.
	 *
	 * <p>Three queries total regardless of page size: the search itself, then the profiles for this
	 * page (which brings their subjects and locations), then the catalog. Not one per row.
	 */
	private List<TutorSearchResult> enrich(List<Object[]> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}

		List<Long> ids = rows.stream().map(row -> ((Number) row[0]).longValue()).toList();

		Map<Long, TutorProfile> profilesById = profiles.findAllById(ids).stream()
				.collect(Collectors.toMap(TutorProfile::getId, p -> p));

		Map<Long, Subject> subjectsById = subjects.findAll().stream()
				.collect(Collectors.toMap(Subject::getId, s -> s));
		Map<Long, Location> locationsById = locations.findAll().stream()
				.collect(Collectors.toMap(Location::getId, l -> l));

		Set<Long> idVerified = verifiedTutorIds(profilesById);

		List<TutorSearchResult> results = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			Long id = ((Number) row[0]).longValue();
			TutorProfile profile = profilesById.get(id);
			if (profile == null) {
				// Published between the search and the enrichment, or deleted. Skipping is
				// preferable to a half-populated row.
				continue;
			}

			results.add(new TutorSearchResult(
					id,
					(String) row[1],
					(String) row[2],
					(String) row[3],
					((Number) row[4]).intValue(),
					row[5] == null ? null : ((Number) row[5]).longValue(),
					row[6] == null ? null : ((Number) row[6]).longValue(),
					(String) row[7],
					Boolean.TRUE.equals(row[8]),
					List.of(profile.getTeachingModes()),
					subjectNames(profile, subjectsById),
					localityName(profile, locationsById),
					row[10] == null ? null : new BigDecimal(row[10].toString()),
					((Number) row[11]).intValue(),
					idVerified.contains(id)));
		}

		return results;
	}

	/**
	 * Which of these tutors have an admin-approved government ID.
	 *
	 * <p>The search projection is keyed by tutor-profile id, but verifications are keyed by user id,
	 * so this maps between the two. One query for the whole page rather than one per tutor.
	 */
	private Set<Long> verifiedTutorIds(Map<Long, TutorProfile> profilesById) {
		if (profilesById.isEmpty()) {
			return Set.of();
		}

		Map<Long, Long> profileIdByUserId = profilesById.values().stream()
				.collect(Collectors.toMap(TutorProfile::getUserId, TutorProfile::getId));

		return verifications
				.findApprovedUserIds(List.copyOf(profileIdByUserId.keySet()), VerificationType.ID)
				.stream()
				.map(profileIdByUserId::get)
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toSet());
	}

	private static List<String> subjectNames(TutorProfile profile, Map<Long, Subject> catalog) {
		Map<Long, String> names = new LinkedHashMap<>();
		for (TutorSubject tutorSubject : profile.getSubjects()) {
			Subject subject = catalog.get(tutorSubject.getSubjectId());
			if (subject != null) {
				names.put(subject.getId(), subject.getName());
			}
		}
		// Capped: a results card shows three, and sending thirty would be payload nobody reads.
		return names.values().stream().limit(5).toList();
	}

	private static String localityName(TutorProfile profile, Map<Long, Location> catalog) {
		for (TutorLocation tutorLocation : profile.getLocations()) {
			Location location = catalog.get(tutorLocation.getLocationId());
			if (location != null) {
				return location.displayName();
			}
		}
		// No listed locality. Online-only tutors are the normal case here.
		return List.of(profile.getTeachingModes()).contains("ONLINE") ? "Online" : null;
	}

	/** Exposed for the {@code M2-02} performance test. */
	@Transactional(readOnly = true)
	public String explain(TutorSearchQuery query) {
		return searchRepository.explainSearch(query);
	}
}
