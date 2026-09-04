-- V17 — Admin moderation (M5-05.3, M5-05.6)
--
-- Two moderation powers that had schema nowhere to live: suspending an account,
-- and taking down a fake or spam requirement.
--
-- NOTE ON NUMBERING: TASKS.md originally reserved V17 for the audit log. That
-- collides with this file, which M5-05 needs first and which the audit log will
-- want to record against. The audit log is V18; TASKS.md M5-08.1 has been
-- corrected in the same commit. (This is the second numbering clash on this
-- project — V10/V11 was the first. Always check the directory before reserving.)
--
-- Forward-only: never edit this file once applied.

-- Account suspension ----------------------------------------------------------
--
-- users.status already carried SUSPENDED from V2; what was missing was WHY.
--
-- The reason lives on the row rather than only in the audit log, because the
-- login path needs it: a suspended user who is told "account suspended" and
-- nothing else contacts support, and support then has to go digging. The audit
-- log records the history of decisions; this column is the current one, read on
-- a hot path.
ALTER TABLE users ADD COLUMN suspended_at     TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN suspension_reason TEXT;
ALTER TABLE users ADD COLUMN suspended_by     BIGINT REFERENCES users (id) ON DELETE SET NULL;

-- A suspension without a reason is not reviewable months later, and the reason
-- is shown to the user. Enforced here as well as in Java: the database is the
-- last line of defence against a bad manual UPDATE.
ALTER TABLE users ADD CONSTRAINT users_suspension_reason_check CHECK (
    status <> 'SUSPENDED' OR (suspended_at IS NOT NULL AND suspension_reason IS NOT NULL));

COMMENT ON COLUMN users.suspension_reason IS
    'Shown to the suspended user at login. Mandatory whenever status = SUSPENDED.';

-- Requirement moderation ------------------------------------------------------
--
-- REMOVED is a distinct state, not a reuse of CLOSED. CLOSED means the student
-- withdrew their own enquiry; REMOVED means we took it down. Collapsing them
-- would make "how many enquiries did we remove as spam?" unanswerable, and would
-- show a student their own withdrawal in place of a moderation notice.
--
-- A CHECK constraint cannot be altered in place — drop and re-add.
ALTER TABLE requirements DROP CONSTRAINT requirements_status_check;

ALTER TABLE requirements ADD CONSTRAINT requirements_status_check CHECK (
    status IN ('OPEN', 'CAPPED', 'HIRED', 'CLOSED', 'EXPIRED', 'REMOVED'));

ALTER TABLE requirements ADD COLUMN removed_at     TIMESTAMPTZ;
ALTER TABLE requirements ADD COLUMN removal_reason TEXT;
ALTER TABLE requirements ADD COLUMN removed_by     BIGINT REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE requirements ADD CONSTRAINT requirements_removal_reason_check CHECK (
    status <> 'REMOVED' OR (removed_at IS NOT NULL AND removal_reason IS NOT NULL));

-- The moderation queue: removed enquiries, most recent first, for the admin
-- screen that lists what was taken down and why. Partial, because removals are
-- rare by design — a full index would be almost entirely dead rows.
CREATE INDEX requirements_removed_idx
    ON requirements (removed_at DESC)
    WHERE status = 'REMOVED';

COMMENT ON COLUMN requirements.removal_reason IS
    'Sent to the student. Mandatory whenever status = REMOVED.';

-- Notification types ----------------------------------------------------------
--
-- Same drop-and-recreate as V16: one readable list beats a chain of edits when
-- checking it against NotificationType.java.
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
    'REQUIREMENT_REMOVED'));

-- Admin user search -----------------------------------------------------------
--
-- The admin user list sorts by signup date within a role/status filter.
-- users_role_status_idx (V2) narrows, but leaves the sort to a heap sort over
-- the matches; this makes the common screen an index scan.
CREATE INDEX users_role_status_created_idx ON users (role, status, created_at DESC);
