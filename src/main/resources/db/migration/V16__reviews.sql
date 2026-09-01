-- V16 — Reviews, moderation and tutor replies
--
-- The last piece of the public marketplace loop. Ratings are not decoration
-- here: tutor_profiles.avg_rating has existed since V6, is indexed for search
-- sorting in V8, backs the minRating filter, and orders the lead-notification
-- fan-out. Until this table exists every tutor is tied at NULL and all four of
-- those rank on nothing. This migration supplies the writer.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE reviews (
    id                       BIGSERIAL   PRIMARY KEY,

    -- Both are user ids, not profile ids. lead_unlocks, credit_wallets and
    -- refund_requests all key the tutor by account, and eligibility here is a
    -- join against lead_unlocks — a profile id would need a translation step on
    -- the one query that decides whether a review is allowed at all.
    tutor_id                 BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    student_id               BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    rating                   SMALLINT    NOT NULL,
    title                    VARCHAR(160),
    body                     VARCHAR(2000),

    status                   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    moderated_by             BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    moderated_at             TIMESTAMPTZ,
    rejection_reason         VARCHAR(500),

    -- The tutor's single public answer. It carries its own moderation status:
    -- an approved review can hold a pending reply, and the two are decided
    -- independently. A bare text column with no status — the shape originally
    -- written into SOURCE_OF_TRUTH.md section 5 — left nowhere for a reply to
    -- wait, which would have made M5-03.2 unimplementable without a second
    -- migration.
    tutor_reply              VARCHAR(2000),
    tutor_reply_status       VARCHAR(16),
    tutor_reply_at           TIMESTAMPTZ,
    tutor_reply_moderated_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Matches tutor_rating_check on tutor_profiles, so an aggregate can never
    -- land outside the range its own column permits.
    CONSTRAINT reviews_rating_check CHECK (rating BETWEEN 1 AND 5),

    CONSTRAINT reviews_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- Nobody may review themselves. Cheap to state here, and impossible to
    -- forget later.
    CONSTRAINT reviews_not_self CHECK (tutor_id <> student_id),

    -- A rejection must say why: the student is being told their words will not
    -- be published, and "rejected" alone reads as censorship.
    CONSTRAINT reviews_rejection_has_reason CHECK (
        status <> 'REJECTED' OR rejection_reason IS NOT NULL),

    CONSTRAINT reviews_decision_recorded CHECK (
        status = 'PENDING' OR moderated_at IS NOT NULL),

    -- The reply's three columns move together or not at all. Without this a
    -- reply could exist with no status and be invisible to both the moderation
    -- queue and the public read — published nowhere, waiting nowhere.
    CONSTRAINT reviews_reply_status_check CHECK (
        tutor_reply_status IS NULL
        OR tutor_reply_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT reviews_reply_consistent CHECK (
        (tutor_reply IS NULL AND tutor_reply_status IS NULL AND tutor_reply_at IS NULL)
        OR (tutor_reply IS NOT NULL AND tutor_reply_status IS NOT NULL
            AND tutor_reply_at IS NOT NULL))
);

-- One review per student-tutor pair (SOURCE_OF_TRUTH.md section 3.6). Enforced
-- by the database, not by a check-then-insert: two submissions arriving
-- together would both find no existing review and both write one.
CREATE UNIQUE INDEX reviews_one_per_pair_idx ON reviews (tutor_id, student_id);

-- The public read on a tutor's profile page. Covers the only query a visitor
-- makes: this tutor's approved reviews, newest first.
CREATE INDEX reviews_tutor_public_idx ON reviews (tutor_id, created_at DESC)
    WHERE status = 'APPROVED';

-- The moderation queue, oldest first. Partial for the same reason as
-- verifications: PENDING is a small slice and the only one queried
-- interactively. A review sitting unmoderated is a student who believes they
-- were ignored, so this queue is meant to be short.
CREATE INDEX reviews_moderation_queue_idx ON reviews (created_at)
    WHERE status = 'PENDING';

-- The separate queue for replies awaiting moderation.
CREATE INDEX reviews_reply_queue_idx ON reviews (tutor_reply_at)
    WHERE tutor_reply_status = 'PENDING';

-- A student's own list, to show them what they have written and its status.
CREATE INDEX reviews_student_idx ON reviews (student_id);

CREATE TRIGGER trg_reviews_updated_at
    BEFORE UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE reviews IS
    'Aggregates live on tutor_profiles and are recomputed from this table on every approval, never incremented. See RatingAggregator.';

COMMENT ON COLUMN reviews.tutor_id IS
    'users.id, not tutor_profiles.id — eligibility joins lead_unlocks, which keys the tutor by account.';

-- Notification types for reviews ---------------------------------------------
-- Rewritten in full rather than amended, following V13: Postgres cannot add a
-- value to a CHECK, and one readable list beats a chain of edits when checking
-- it against NotificationType.java.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'NEW_MATCHING_LEAD',
    'TUTOR_RESPONDED',
    'LOW_CREDIT_BALANCE',
    'VERIFICATION_APPROVED',
    'VERIFICATION_REJECTED',
    'REQUIREMENT_EXPIRING',
    'SIGNUP_BONUS_GRANTED',
    'CREDITS_PURCHASED',
    'CREDITS_EXPIRING',
    'REFUND_APPROVED',
    'REFUND_REJECTED',
    'REVIEW_PUBLISHED',
    'REVIEW_REJECTED',
    'REVIEW_REPLY_PUBLISHED'));
