-- V8 — Search indexes
--
-- Written after the search query rather than before it, so each index exists for
-- a predicate that is actually used. Speculative indexes are not free: every one
-- slows every write and consumes memory that the useful ones want.
--
-- Verified with EXPLAIN ANALYZE in TutorSearchPerformanceTest.
--
-- Forward-only: never edit this file once applied.

-- The base predicate. Every search filters on is_published, so a partial index
-- keeps unpublished drafts out of the index entirely rather than merely
-- filtering them out after a read.
--
-- The V6 index covered (is_published, avg_rating). This adds review_count,
-- because the default RELEVANCE sort breaks rating ties by it — a 5.0 from
-- forty people should outrank a 5.0 from one.
CREATE INDEX tutor_profiles_relevance_idx
    ON tutor_profiles (avg_rating DESC NULLS LAST, review_count DESC, id)
    WHERE is_published;

-- The other sort orders. Each is partial on is_published for the same reason.
CREATE INDEX tutor_profiles_fee_sort_idx
    ON tutor_profiles (fee_min_paise ASC NULLS LAST, id)
    WHERE is_published;

CREATE INDEX tutor_profiles_experience_idx
    ON tutor_profiles (experience_years DESC, id)
    WHERE is_published;

CREATE INDEX tutor_profiles_recent_idx
    ON tutor_profiles (updated_at DESC, id)
    WHERE is_published;

-- Teaching mode is a varchar[] queried with '= ANY(...)'. A GIN index is what
-- makes array containment indexable; a btree here would be ignored.
CREATE INDEX tutor_profiles_modes_idx
    ON tutor_profiles USING gin (teaching_modes);

-- Free-text search uses ILIKE '%term%'. A leading wildcard defeats a btree
-- index completely and forces a sequential scan; pg_trgm (installed in V1) makes
-- it indexable. This is the single most important index for search latency once
-- there is real data.
CREATE INDEX subjects_name_search_idx
    ON subjects USING gin (name gin_trgm_ops);

-- Subject and location filters are EXISTS subqueries against these join tables.
-- The V6 indexes are on (tutor_id, subject_id) and (tutor_id, location_id);
-- these are the reverse direction, which is how the subqueries actually probe —
-- given a subject, find its tutors.
CREATE INDEX tutor_subjects_lookup_idx ON tutor_subjects (subject_id, tutor_id);
CREATE INDEX tutor_locations_lookup_idx ON tutor_locations (location_id, tutor_id);

-- Board and grade filters test membership in a bigint[] on tutor_subjects.
CREATE INDEX tutor_subjects_boards_idx ON tutor_subjects USING gin (board_ids);
CREATE INDEX tutor_subjects_grades_idx ON tutor_subjects USING gin (grade_level_ids);

-- The verified-only filter is an EXISTS on approved ID verifications. Partial,
-- because only approved rows are ever probed by search.
CREATE INDEX verifications_approved_id_idx
    ON verifications (user_id)
    WHERE status = 'APPROVED' AND type = 'ID';

COMMENT ON INDEX tutor_profiles_relevance_idx IS
    'Default search sort. Partial on is_published so drafts never enter the index.';
COMMENT ON INDEX subjects_name_search_idx IS
    'Trigram index for ILIKE %term% free-text search, which a btree cannot serve.';
