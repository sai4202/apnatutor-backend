-- V9 — Requirements and lead unlocks
--
-- The core marketplace transaction. A student posts a requirement for free; a
-- tutor spends credits to reveal the contact details.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE requirements (
    id                   BIGSERIAL   PRIMARY KEY,
    student_id           BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    subject_id           BIGINT      NOT NULL REFERENCES subjects (id) ON DELETE RESTRICT,
    grade_level_id       BIGINT      REFERENCES grade_levels (id) ON DELETE SET NULL,
    board_id             BIGINT      REFERENCES boards (id) ON DELETE SET NULL,
    location_id          BIGINT      REFERENCES locations (id) ON DELETE SET NULL,

    mode                 VARCHAR(16) NOT NULL,
    budget_amount_paise  BIGINT,
    budget_unit          VARCHAR(16),
    frequency            VARCHAR(60),
    preferred_timing     VARCHAR(200),
    gender_preference    VARCHAR(16),
    description          TEXT,

    status               VARCHAR(16) NOT NULL DEFAULT 'OPEN',

    -- THE PRICE IS LOCKED HERE, AT CREATION (SOURCE_OF_TRUTH.md §3.1).
    --
    -- Not computed at unlock time. A tutor looking at a lead priced at 5 credits
    -- must be charged 5 credits, even if we change the pricing bands between
    -- their reading the feed and their tapping unlock. Repricing a lead under
    -- someone mid-decision is the kind of thing that loses a tutor permanently.
    unlock_cost_credits  INTEGER     NOT NULL,

    -- Denormalised counter, maintained in the same transaction as the unlock.
    -- The authority is COUNT(*) over active lead_unlocks; this exists so the
    -- cap check and the feed do not need that count on every read.
    unlock_count         INTEGER     NOT NULL DEFAULT 0,

    expires_at           TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT requirements_mode_check CHECK (
        mode IN ('STUDENT_HOME', 'TUTOR_PLACE', 'ONLINE')),
    CONSTRAINT requirements_status_check CHECK (
        status IN ('OPEN', 'CAPPED', 'HIRED', 'CLOSED', 'EXPIRED')),
    CONSTRAINT requirements_budget_unit_check CHECK (
        budget_unit IS NULL OR budget_unit IN ('PER_HOUR', 'PER_MONTH')),
    CONSTRAINT requirements_gender_check CHECK (
        gender_preference IS NULL OR gender_preference IN ('MALE', 'FEMALE', 'ANY')),
    CONSTRAINT requirements_budget_positive CHECK (
        budget_amount_paise IS NULL OR budget_amount_paise >= 0),
    -- A lead must cost something. A zero-credit lead would be a free contact
    -- reveal, which is the whole business model given away.
    CONSTRAINT requirements_cost_positive CHECK (unlock_cost_credits > 0),
    CONSTRAINT requirements_unlock_count_check CHECK (unlock_count >= 0)
);

-- The tutor lead feed: open requirements, newest first.
CREATE INDEX requirements_feed_idx
    ON requirements (subject_id, created_at DESC)
    WHERE status = 'OPEN';

-- A student's own dashboard.
CREATE INDEX requirements_student_idx ON requirements (student_id, created_at DESC);

-- The expiry job.
CREATE INDEX requirements_expiry_idx
    ON requirements (expires_at)
    WHERE status IN ('OPEN', 'CAPPED');

CREATE INDEX requirements_location_idx ON requirements (location_id) WHERE status = 'OPEN';

CREATE TRIGGER trg_requirements_updated_at
    BEFORE UPDATE ON requirements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN requirements.unlock_cost_credits IS
    'Locked at creation. Repricing must never move the cost of a lead a tutor is already looking at.';

-- Lead unlocks -------------------------------------------------------------------
--
-- SOURCE_OF_TRUTH.md Invariant 2: this is a GENERIC ENGAGEMENT RECORD, not an
-- unlock-specific table. engagement_type is 'UNLOCK' today; a v2 booking becomes
-- another type on the same connection graph, so reviews, notifications and the
-- "my students" / "my tutors" lists keep working untouched.
CREATE TABLE lead_unlocks (
    id              BIGSERIAL   PRIMARY KEY,
    requirement_id  BIGINT      NOT NULL REFERENCES requirements (id) ON DELETE CASCADE,

    -- The tutor's USER id, not their profile id. Billing, verification and
    -- unlocks are all account-level concerns and key off the same thing; only
    -- search and public profiles use the profile id.
    tutor_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    engagement_type VARCHAR(16) NOT NULL DEFAULT 'UNLOCK',
    credits_spent   INTEGER     NOT NULL,
    intro_message   VARCHAR(1000),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    unlocked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lead_unlocks_type_check CHECK (engagement_type IN ('UNLOCK', 'BOOKING')),
    CONSTRAINT lead_unlocks_status_check CHECK (status IN ('ACTIVE', 'REFUNDED')),
    CONSTRAINT lead_unlocks_credits_check CHECK (credits_spent >= 0)
);

-- THE CONSTRAINT THAT PREVENTS DOUBLE-CHARGING.
--
-- A tutor must never pay twice for the same lead. Application-level checks race
-- with themselves under concurrent requests; this does not. It is also what makes
-- the unlock endpoint safe to retry, because a duplicate insert fails loudly
-- rather than silently taking more credits.
CREATE UNIQUE INDEX lead_unlocks_one_per_tutor_idx
    ON lead_unlocks (requirement_id, tutor_id);

CREATE INDEX lead_unlocks_tutor_idx ON lead_unlocks (tutor_id, unlocked_at DESC);
CREATE INDEX lead_unlocks_requirement_idx ON lead_unlocks (requirement_id);

CREATE TRIGGER trg_lead_unlocks_updated_at
    BEFORE UPDATE ON lead_unlocks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE lead_unlocks IS
    'Generic tutor-student engagement record (SoT Invariant 2). A v2 booking is another engagement_type here, not a parallel table.';
COMMENT ON INDEX lead_unlocks_one_per_tutor_idx IS
    'Prevents double-charging. An application check would race; this does not.';
