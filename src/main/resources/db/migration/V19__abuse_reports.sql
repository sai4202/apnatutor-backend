-- V19 — Abuse reports (M5-09)
--
-- Somebody reporting a tutor, a student, a review or an enquiry.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE abuse_reports (
    id            BIGSERIAL    PRIMARY KEY,

    -- Nullable, because not every report comes from a person. The platform
    -- raises its own when a signal crosses a threshold - a tutor disputing a
    -- third of the leads they buy, for instance - and those have no reporter.
    -- The CHECK below makes sure a USER report always names one.
    reporter_id   BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    source        VARCHAR(16)  NOT NULL DEFAULT 'USER',

    -- What is being reported. Not a foreign key: the four kinds live in four
    -- tables, and a polymorphic FK is not expressible. The pair is validated in
    -- the service, which can also refuse a subject that does not exist.
    subject_type  VARCHAR(16)  NOT NULL,
    subject_id    BIGINT       NOT NULL,

    -- A code, not free text, for the same reason refund_requests uses one: the
    -- point of collecting reasons is to count them.
    reason        VARCHAR(32)  NOT NULL,
    details       VARCHAR(1000),

    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    reviewed_by   BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at   TIMESTAMPTZ,
    decision_note VARCHAR(1000),

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT abuse_reports_source_check CHECK (source IN ('USER', 'SYSTEM')),
    CONSTRAINT abuse_reports_reporter_check CHECK (
        source <> 'USER' OR reporter_id IS NOT NULL),
    CONSTRAINT abuse_reports_subject_check CHECK (
        subject_type IN ('TUTOR', 'STUDENT', 'REVIEW', 'REQUIREMENT')),
    CONSTRAINT abuse_reports_status_check CHECK (
        status IN ('OPEN', 'UPHELD', 'DISMISSED')),
    CONSTRAINT abuse_reports_reason_check CHECK (
        reason IN ('SPAM', 'FAKE_PROFILE', 'FAKE_ENQUIRY', 'HARASSMENT',
                   'OFF_PLATFORM_SOLICITATION', 'MISLEADING_CLAIMS',
                   'INAPPROPRIATE_CONTENT', 'HIGH_DISPUTE_RATE', 'OTHER')),
    CONSTRAINT abuse_reports_decision_check CHECK (
        status = 'OPEN' OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))
);

-- One open report per person per subject.
--
-- Partial, so the same person may report the same tutor again after the first
-- report was decided - a second incident is real information. What it stops is
-- one person filing the same complaint fifty times to make the count look like
-- a pattern, because the count is exactly what a moderator reads.
CREATE UNIQUE INDEX abuse_reports_one_open_per_reporter_idx
    ON abuse_reports (reporter_id, subject_type, subject_id)
    WHERE status = 'OPEN' AND reporter_id IS NOT NULL;

-- The triage queue: open reports, oldest first.
CREATE INDEX abuse_reports_queue_idx
    ON abuse_reports (created_at)
    WHERE status = 'OPEN';

-- "What has been reported about this tutor?" - asked before every decision, and
-- the reason several separate reporters matter more than one loud one.
CREATE INDEX abuse_reports_subject_idx
    ON abuse_reports (subject_type, subject_id, created_at DESC);

CREATE TRIGGER trg_abuse_reports_updated_at
    BEFORE UPDATE ON abuse_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN abuse_reports.source IS
    'USER for a person, SYSTEM for a signal the platform raised about itself.';

-- Notification types ----------------------------------------------------------
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
    'REVIEW_REPLY_PUBLISHED',
    'ACCOUNT_SUSPENDED',
    'ACCOUNT_REINSTATED',
    'REQUIREMENT_REMOVED',
    'ABUSE_REPORT_REVIEWED'));
