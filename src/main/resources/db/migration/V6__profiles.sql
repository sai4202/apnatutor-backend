-- V6 — Student and tutor profiles
--
-- Tutor profiles are the core inventory of the marketplace: search, the lead
-- feed, and every unlock depends on them existing.
--
-- Forward-only: never edit this file once applied.

-- Student profiles --------------------------------------------------------------
-- Deliberately thin. A parent should be able to post a requirement almost
-- immediately; every field asked for here is a chance to abandon the funnel.
CREATE TABLE student_profiles (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(120),
    location_id BIGINT      REFERENCES locations (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_student_profiles_updated_at
    BEFORE UPDATE ON student_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Tutor profiles ----------------------------------------------------------------
CREATE TABLE tutor_profiles (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,

    display_name        VARCHAR(120),
    headline            VARCHAR(160),
    bio                 TEXT,
    photo_url           VARCHAR(500),
    gender              VARCHAR(16),
    date_of_birth       DATE,
    experience_years    INTEGER      NOT NULL DEFAULT 0,

    -- Money in paise as BIGINT, never floating point (SOURCE_OF_TRUTH.md §5).
    fee_min_paise       BIGINT,
    fee_max_paise       BIGINT,
    fee_unit            VARCHAR(16),
    fee_negotiable      BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Postgres array rather than a join table: this is a fixed set of at most
    -- three values, always read with the profile and never queried
    -- independently. A join table here would add a join to the hottest query in
    -- the application for no benefit.
    teaching_modes      VARCHAR(16)[] NOT NULL DEFAULT '{}',
    travel_radius_km    INTEGER      NOT NULL DEFAULT 0,
    languages           VARCHAR(60)[] NOT NULL DEFAULT '{}',

    offers_demo         BOOLEAN      NOT NULL DEFAULT FALSE,
    availability_note   VARCHAR(400),

    -- Derived, never client-supplied. Recomputed on write and on review
    -- approval; a client-provided rating would be trivially forged.
    profile_completeness INTEGER     NOT NULL DEFAULT 0,
    avg_rating          NUMERIC(2, 1),
    review_count        INTEGER      NOT NULL DEFAULT 0,
    response_rate       INTEGER,

    -- A profile is invisible until the tutor publishes it, and it cannot be
    -- published while incomplete. Search filters on this column.
    is_published        BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT tutor_gender_check CHECK (
        gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT tutor_fee_unit_check CHECK (
        fee_unit IS NULL OR fee_unit IN ('PER_HOUR', 'PER_MONTH')),
    CONSTRAINT tutor_fee_range_check CHECK (
        fee_min_paise IS NULL OR fee_max_paise IS NULL OR fee_max_paise >= fee_min_paise),
    CONSTRAINT tutor_fee_positive_check CHECK (
        (fee_min_paise IS NULL OR fee_min_paise >= 0)
        AND (fee_max_paise IS NULL OR fee_max_paise >= 0)),
    CONSTRAINT tutor_experience_check CHECK (experience_years BETWEEN 0 AND 70),
    CONSTRAINT tutor_radius_check CHECK (travel_radius_km BETWEEN 0 AND 100),
    CONSTRAINT tutor_completeness_check CHECK (profile_completeness BETWEEN 0 AND 100),
    CONSTRAINT tutor_rating_check CHECK (
        avg_rating IS NULL OR (avg_rating >= 1 AND avg_rating <= 5))
);

-- The hot path for search: published profiles ordered by rating.
CREATE INDEX tutor_profiles_published_idx
    ON tutor_profiles (is_published, avg_rating DESC NULLS LAST)
    WHERE is_published;

CREATE INDEX tutor_profiles_fee_idx ON tutor_profiles (fee_min_paise) WHERE is_published;

-- Fuzzy name search, using pg_trgm from V1.
CREATE INDEX tutor_profiles_name_trgm_idx
    ON tutor_profiles USING gin (display_name gin_trgm_ops);

CREATE TRIGGER trg_tutor_profiles_updated_at
    BEFORE UPDATE ON tutor_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN tutor_profiles.avg_rating IS 'Derived from approved reviews only. Never accepted from a client.';
COMMENT ON COLUMN tutor_profiles.is_published IS 'Search shows published profiles only. Cannot be set while incomplete.';

-- What a tutor teaches ------------------------------------------------------------
-- Per-subject fees, grades and boards, because a tutor may charge differently
-- for Class 12 Physics than for Class 8 Maths, and may teach one subject only
-- for CBSE.
CREATE TABLE tutor_subjects (
    id              BIGSERIAL   PRIMARY KEY,
    tutor_id        BIGINT      NOT NULL REFERENCES tutor_profiles (id) ON DELETE CASCADE,
    subject_id      BIGINT      NOT NULL REFERENCES subjects (id) ON DELETE RESTRICT,
    fee_paise       BIGINT,
    fee_unit        VARCHAR(16),
    grade_level_ids BIGINT[]    NOT NULL DEFAULT '{}',
    board_ids       BIGINT[]    NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT tutor_subject_fee_unit_check CHECK (
        fee_unit IS NULL OR fee_unit IN ('PER_HOUR', 'PER_MONTH')),
    CONSTRAINT tutor_subject_fee_check CHECK (fee_paise IS NULL OR fee_paise >= 0)
);

-- One row per tutor-subject pair. Without this a tutor could list Mathematics
-- twice with different fees and search results would show them twice.
CREATE UNIQUE INDEX tutor_subjects_unique_idx ON tutor_subjects (tutor_id, subject_id);
CREATE INDEX tutor_subjects_subject_idx ON tutor_subjects (subject_id);

CREATE TRIGGER trg_tutor_subjects_updated_at
    BEFORE UPDATE ON tutor_subjects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Where a tutor will travel --------------------------------------------------------
CREATE TABLE tutor_locations (
    id          BIGSERIAL   PRIMARY KEY,
    tutor_id    BIGINT      NOT NULL REFERENCES tutor_profiles (id) ON DELETE CASCADE,
    location_id BIGINT      NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX tutor_locations_unique_idx ON tutor_locations (tutor_id, location_id);
CREATE INDEX tutor_locations_location_idx ON tutor_locations (location_id);

CREATE TRIGGER trg_tutor_locations_updated_at
    BEFORE UPDATE ON tutor_locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Qualifications --------------------------------------------------------------------
-- document_url is admin-only. These are degree certificates and ID documents;
-- serving them publicly would be a data-protection incident, not a feature.
CREATE TABLE tutor_qualifications (
    id           BIGSERIAL    PRIMARY KEY,
    tutor_id     BIGINT       NOT NULL REFERENCES tutor_profiles (id) ON DELETE CASCADE,
    degree       VARCHAR(160) NOT NULL,
    institution  VARCHAR(200) NOT NULL,
    year         INTEGER,
    document_url VARCHAR(500),
    is_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT tutor_qualification_year_check CHECK (
        year IS NULL OR year BETWEEN 1950 AND 2100)
);

CREATE INDEX tutor_qualifications_tutor_idx ON tutor_qualifications (tutor_id);

CREATE TRIGGER trg_tutor_qualifications_updated_at
    BEFORE UPDATE ON tutor_qualifications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN tutor_qualifications.document_url IS 'Admin-only. Never served on a public endpoint.';
