package com.apnatutor.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apnatutor.search.dto.SearchDtos.SortOrder;
import com.apnatutor.search.dto.SearchDtos.TutorSearchQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

/**
 * The tutor search query.
 *
 * <h2>Why native SQL rather than JPA</h2>
 *
 * Two reasons, both concrete. {@code teaching_modes} is a Postgres {@code varchar[]} and JPQL has
 * no way to express {@code = ANY(...)}. And this is the hottest query in the application — the one
 * {@code M2-02} has to prove runs without a sequential scan — so the SQL that actually reaches the
 * planner needs to be the SQL written here, not whatever Hibernate composes.
 *
 * <h2>Every value is a bound parameter</h2>
 *
 * The WHERE clause is assembled from a fixed set of fragments; only the <em>shape</em> is dynamic.
 * No user input is ever concatenated into the string. Sort order comes from an enum, so an
 * arbitrary string cannot reach the ORDER BY either — which is the usual way a "safe" dynamic query
 * turns out to be injectable.
 *
 * <h2>Only published profiles, always</h2>
 *
 * The predicate is not optional and not a parameter. An unpublished profile is a half-finished
 * draft, and there is no caller that should see one.
 */
@Repository
public class TutorSearchRepository {

	private static final String SELECT_COLUMNS = """
			SELECT tp.id,
			       tp.display_name,
			       tp.headline,
			       tp.photo_url,
			       tp.experience_years,
			       tp.fee_min_paise,
			       tp.fee_max_paise,
			       tp.fee_unit,
			       tp.fee_negotiable,
			       tp.teaching_modes,
			       tp.avg_rating,
			       tp.review_count,
			       tp.updated_at
			""";

	private final EntityManager entityManager;

	public TutorSearchRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	/** A WHERE clause plus the parameters it expects. */
	private record Filter(String sql, Map<String, Object> parameters) {
	}

	public List<Object[]> search(TutorSearchQuery query, int offset, int limit) {
		Filter filter = buildFilter(query);

		String sql = SELECT_COLUMNS
				+ " FROM tutor_profiles tp "
				+ filter.sql()
				+ orderBy(query.sort())
				+ " LIMIT :limit OFFSET :offset";

		Query nativeQuery = entityManager.createNativeQuery(sql);
		filter.parameters().forEach(nativeQuery::setParameter);
		nativeQuery.setParameter("limit", limit);
		nativeQuery.setParameter("offset", offset);

		@SuppressWarnings("unchecked")
		List<Object[]> rows = nativeQuery.getResultList();
		return rows;
	}

	public long count(TutorSearchQuery query) {
		Filter filter = buildFilter(query);

		Query nativeQuery = entityManager.createNativeQuery(
				"SELECT COUNT(*) FROM tutor_profiles tp " + filter.sql());
		filter.parameters().forEach(nativeQuery::setParameter);

		return ((Number) nativeQuery.getSingleResult()).longValue();
	}

	/** Counts for the filter sidebar, so a visitor can see what narrowing would cost them. */
	public long countMatching(TutorSearchQuery query, String extraSql) {
		Filter filter = buildFilter(query);

		Query nativeQuery = entityManager.createNativeQuery(
				"SELECT COUNT(*) FROM tutor_profiles tp " + filter.sql() + " AND " + extraSql);
		filter.parameters().forEach(nativeQuery::setParameter);

		return ((Number) nativeQuery.getSingleResult()).longValue();
	}

	private Filter buildFilter(TutorSearchQuery query) {
		List<String> conditions = new ArrayList<>();
		Map<String, Object> parameters = new LinkedHashMap<>();

		// Not optional, not a parameter. An unpublished profile is a draft.
		conditions.add("tp.is_published = TRUE");

		if (hasText(query.subject())) {
			conditions.add("""
					EXISTS (SELECT 1 FROM tutor_subjects ts
					        JOIN subjects s ON s.id = ts.subject_id
					        WHERE ts.tutor_id = tp.id AND s.slug = :subjectSlug)""");
			parameters.put("subjectSlug", query.subject());
		}

		if (hasText(query.location())) {
			// An online tutor matches every location. They can teach a student in any city, and
			// excluding them from a city search would hide exactly the tutors most able to help.
			conditions.add("""
					(EXISTS (SELECT 1 FROM tutor_locations tl
					         JOIN locations l ON l.id = tl.location_id
					         WHERE tl.tutor_id = tp.id
					           AND (l.slug = :locationSlug
					                OR l.city = (SELECT city FROM locations WHERE slug = :locationSlug LIMIT 1)))
					 OR 'ONLINE' = ANY(tp.teaching_modes))""");
			parameters.put("locationSlug", query.location());
		}

		if (hasText(query.board())) {
			conditions.add("""
					EXISTS (SELECT 1 FROM tutor_subjects ts
					        JOIN boards b ON b.id = ANY(ts.board_ids)
					        WHERE ts.tutor_id = tp.id AND b.slug = :boardSlug)""");
			parameters.put("boardSlug", query.board());
		}

		if (hasText(query.grade())) {
			conditions.add("""
					EXISTS (SELECT 1 FROM tutor_subjects ts
					        JOIN grade_levels g ON g.id = ANY(ts.grade_level_ids)
					        WHERE ts.tutor_id = tp.id AND g.slug = :gradeSlug)""");
			parameters.put("gradeSlug", query.grade());
		}

		if (hasText(query.mode())) {
			conditions.add(":mode = ANY(tp.teaching_modes)");
			parameters.put("mode", query.mode());
		}

		if (hasText(query.gender())) {
			conditions.add("tp.gender = :gender");
			parameters.put("gender", query.gender());
		}

		if (query.minExperienceYears() != null) {
			conditions.add("tp.experience_years >= :minExperience");
			parameters.put("minExperience", query.minExperienceYears());
		}

		if (query.minRating() != null) {
			conditions.add("tp.avg_rating >= :minRating");
			parameters.put("minRating", query.minRating());
		}

		// Fee bounds compare against the tutor's starting fee. Comparing against the range would
		// exclude a tutor whose lowest rate is inside a parent's budget merely because their
		// highest is not — which is the opposite of helpful.
		if (query.feeMinPaise() != null) {
			conditions.add("tp.fee_min_paise >= :feeMin");
			parameters.put("feeMin", query.feeMinPaise());
		}
		if (query.feeMaxPaise() != null) {
			conditions.add("tp.fee_min_paise <= :feeMax");
			parameters.put("feeMax", query.feeMaxPaise());
		}

		if (Boolean.TRUE.equals(query.verifiedOnly())) {
			conditions.add("""
					EXISTS (SELECT 1 FROM verifications v
					        WHERE v.user_id = tp.user_id
					          AND v.type = 'ID'
					          AND v.status = 'APPROVED')""");
		}

		if (hasText(query.q())) {
			// Trigram similarity on the name, plus a subject-name match, so "maths" finds a tutor
			// who teaches Mathematics as well as one called that. ILIKE with a leading wildcard
			// would force a sequential scan; the GIN trigram index in V8 makes this indexable.
			conditions.add("""
					(tp.display_name ILIKE :qLike
					 OR EXISTS (SELECT 1 FROM tutor_subjects ts
					            JOIN subjects s ON s.id = ts.subject_id
					            WHERE ts.tutor_id = tp.id AND s.name ILIKE :qLike))""");
			parameters.put("qLike", "%" + query.q().trim() + "%");
		}

		return new Filter(" WHERE " + String.join(" AND ", conditions), parameters);
	}

	/**
	 * Sort clause. Built from an enum, never from caller input, so nothing arbitrary can reach the
	 * ORDER BY.
	 *
	 * <p>{@code NULLS LAST} throughout: a tutor with no rating yet should sit below one with a
	 * rating, not above. Postgres sorts nulls first on DESC by default, which would put every brand
	 * new profile at the top of a "best rated" list.
	 */
	private static String orderBy(SortOrder sort) {
		SortOrder order = sort == null ? SortOrder.RELEVANCE : sort;
		return switch (order) {
			// A 5.0 from forty people should outrank a 5.0 from one, so review count breaks ties.
			case RELEVANCE -> " ORDER BY tp.avg_rating DESC NULLS LAST, tp.review_count DESC, tp.id";
			case RATING -> " ORDER BY tp.avg_rating DESC NULLS LAST, tp.id";
			case FEE_LOW_TO_HIGH -> " ORDER BY tp.fee_min_paise ASC NULLS LAST, tp.id";
			case FEE_HIGH_TO_LOW -> " ORDER BY tp.fee_min_paise DESC NULLS LAST, tp.id";
			case EXPERIENCE -> " ORDER BY tp.experience_years DESC, tp.id";
			case RECENTLY_ACTIVE -> " ORDER BY tp.updated_at DESC, tp.id";
		};
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	/** Exposed for the {@code EXPLAIN ANALYZE} check in {@code M2-02}. */
	public String explainSearch(TutorSearchQuery query) {
		Filter filter = buildFilter(query);
		String sql = "EXPLAIN ANALYZE " + SELECT_COLUMNS
				+ " FROM tutor_profiles tp " + filter.sql() + orderBy(query.sort()) + " LIMIT 20";

		Query nativeQuery = entityManager.createNativeQuery(sql);
		filter.parameters().forEach(nativeQuery::setParameter);

		@SuppressWarnings("unchecked")
		List<String> plan = nativeQuery.getResultList();
		return String.join("\n", plan);
	}

	/** Widened for the fee columns, which are BIGINT paise. */
	public static BigDecimal toBigDecimal(Object value) {
		return value == null ? null : new BigDecimal(value.toString());
	}
}
