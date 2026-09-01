-- V7 — Verification
--
-- Admin-reviewed claims about a user: email, government ID, education.
--
-- PHONE is deliberately NOT a row here. It already lives on
-- users.phone_verified_at, written by the OTP flow, and duplicating it would
-- create two sources of truth for the same fact — the kind that drift apart
-- quietly and are then impossible to reconcile. Phone status is read from the
-- user; this table covers only the claims that need a document and a human
-- decision.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE verifications (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type             VARCHAR(24) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',

    -- Storage key for the submitted document. ADMIN-ONLY: these are Aadhaar and
    -- PAN scans and degree certificates. Never mapped into a public DTO, and
    -- only reachable through /api/v1/admin/files/**.
    document_url     VARCHAR(500),

    reviewed_by      BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at      TIMESTAMPTZ,
    rejection_reason VARCHAR(500),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT verifications_type_check CHECK (type IN ('EMAIL', 'ID', 'EDUCATION')),
    CONSTRAINT verifications_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- A rejection must say why. A tutor told only "rejected" cannot fix
    -- anything, and will either give up or resubmit the same document.
    CONSTRAINT verifications_rejection_has_reason CHECK (
        status <> 'REJECTED' OR rejection_reason IS NOT NULL),

    -- An approval or rejection must record who made it and when.
    CONSTRAINT verifications_review_recorded CHECK (
        status = 'PENDING' OR reviewed_at IS NOT NULL)
);

-- One live claim per user per type. Resubmission after a rejection is allowed —
-- the rejected row stays for history — but a user cannot stack several pending
-- requests for the same thing and flood the review queue.
CREATE UNIQUE INDEX verifications_one_live_per_type_idx
    ON verifications (user_id, type)
    WHERE status IN ('PENDING', 'APPROVED');

-- Drives the admin queue: oldest pending first, so nobody waits indefinitely
-- behind a steady stream of newer submissions.
CREATE INDEX verifications_queue_idx
    ON verifications (status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX verifications_user_idx ON verifications (user_id);

CREATE TRIGGER trg_verifications_updated_at
    BEFORE UPDATE ON verifications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN verifications.document_url IS
    'Admin-only storage key. Never served on a public endpoint.';
