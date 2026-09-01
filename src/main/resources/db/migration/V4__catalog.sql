-- V4 — Catalog
--
-- Subjects, boards, grade levels and locations. Reference data that tutor
-- profiles, requirements and every SEO landing page hang off.
--
-- Moved forward from M2 into M1: a tutor profile cannot be built without
-- something to attach subjects and locations to, and "what do you teach, and
-- where" is the centre of the onboarding wizard.
--
-- Forward-only: never edit this file once applied.

-- Subjects --------------------------------------------------------------------
-- A self-referencing tree: Academics -> Class 9-10 Tuition -> Mathematics.
-- Only leaves are selectable by a tutor; branches exist for navigation and for
-- grouping search results.
CREATE TABLE subjects (
    id            BIGSERIAL    PRIMARY KEY,
    parent_id     BIGINT       REFERENCES subjects (id) ON DELETE RESTRICT,
    name          VARCHAR(120) NOT NULL,
    -- Becomes part of a public URL (/tutors/hyderabad/class-10-mathematics).
    -- Effectively permanent once indexed by a search engine, so changing one
    -- later means a redirect, not an edit.
    slug          VARCHAR(140) NOT NULL,
    is_leaf       BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A subject cannot be its own parent. Deeper cycles are prevented by only
    -- ever inserting children after their parents.
    CONSTRAINT subjects_no_self_parent CHECK (parent_id IS DISTINCT FROM id)
);

CREATE UNIQUE INDEX subjects_slug_key ON subjects (slug);
CREATE INDEX subjects_parent_idx ON subjects (parent_id, display_order);
-- Fuzzy matching for the search box, using the pg_trgm extension from V1.
CREATE INDEX subjects_name_trgm_idx ON subjects USING gin (name gin_trgm_ops);

CREATE TRIGGER trg_subjects_updated_at
    BEFORE UPDATE ON subjects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Boards ----------------------------------------------------------------------
-- A parent searching for a CBSE tutor rarely wants a State Board one. This is a
-- first-class filter in India, not a nice-to-have.
CREATE TABLE boards (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    slug          VARCHAR(140) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX boards_slug_key ON boards (slug);

CREATE TRIGGER trg_boards_updated_at
    BEFORE UPDATE ON boards
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Grade levels ----------------------------------------------------------------
CREATE TABLE grade_levels (
    id            BIGSERIAL    PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    slug          VARCHAR(140) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX grade_levels_slug_key ON grade_levels (slug);

CREATE TRIGGER trg_grade_levels_updated_at
    BEFORE UPDATE ON grade_levels
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Locations -------------------------------------------------------------------
-- Deliberately flat rather than a tree: a city row has a NULL locality, and a
-- locality row names its city. Two levels is all this product needs, and a flat
-- table keeps the search query simple, which matters because it is the hottest
-- query in the application.
CREATE TABLE locations (
    id            BIGSERIAL     PRIMARY KEY,
    state         VARCHAR(120)  NOT NULL,
    city          VARCHAR(120)  NOT NULL,
    locality      VARCHAR(120),
    slug          VARCHAR(200)  NOT NULL,
    -- For "within N km" search. Nullable because a locality can be listed before
    -- anyone has geocoded it, and a missing coordinate must not block a signup.
    latitude      NUMERIC(9, 6),
    longitude     NUMERIC(9, 6),
    is_city       BOOLEAN       NOT NULL DEFAULT FALSE,
    display_order INTEGER       NOT NULL DEFAULT 0,
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Keeps the two-level model honest: a city row has no locality, and a
    -- locality row must name one.
    CONSTRAINT locations_city_shape CHECK (
        (is_city AND locality IS NULL) OR (NOT is_city AND locality IS NOT NULL)
    )
);

CREATE UNIQUE INDEX locations_slug_key ON locations (slug);
CREATE INDEX locations_city_idx ON locations (city, display_order);
CREATE INDEX locations_name_trgm_idx ON locations USING gin (
    (coalesce(locality, '') || ' ' || city) gin_trgm_ops);

CREATE TRIGGER trg_locations_updated_at
    BEFORE UPDATE ON locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN locations.slug IS 'Public URL segment. Permanent once indexed.';
COMMENT ON COLUMN subjects.slug IS 'Public URL segment. Permanent once indexed.';
