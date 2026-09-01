-- V14 — Credit refunds and disputes
--
-- The trust mechanic that keeps tutors paying. A tutor who buys a lead and
-- reaches a disconnected number has been sold nothing, and if that is simply
-- their loss they stop buying leads — which costs far more than the credits.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE refund_requests (
    id             BIGSERIAL    PRIMARY KEY,

    -- The unlock being disputed. The tutor is derivable from it, but stored
    -- anyway so "this tutor's dispute rate" is a single-table query — it is
    -- checked on every decision and is the main abuse signal.
    unlock_id      BIGINT       NOT NULL REFERENCES lead_unlocks (id) ON DELETE CASCADE,
    tutor_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requirement_id BIGINT       NOT NULL REFERENCES requirements (id) ON DELETE CASCADE,

    -- A code, not free text. Free text alone cannot be counted, and the whole
    -- point of collecting reasons is to find the requirements and the students
    -- generating them.
    reason         VARCHAR(32)  NOT NULL,
    details        VARCHAR(1000),

    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- What was actually charged, copied at dispute time. A later repricing must
    -- not change what gets refunded.
    credits        INTEGER      NOT NULL,

    reviewed_by    BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    reviewed_at    TIMESTAMPTZ,
    decision_note  VARCHAR(1000),

    -- The compensating ledger entry, once approved. Null until then.
    credit_txn_id  BIGINT       REFERENCES credit_transactions (id),

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT refund_requests_reason_check CHECK (reason IN (
        'WRONG_NUMBER',
        'UNREACHABLE',
        'ALREADY_HIRED',
        'NOT_LOOKING',
        'DUPLICATE_REQUIREMENT',
        'WRONG_SUBJECT_OR_AREA',
        'ABUSIVE',
        'OTHER')),
    CONSTRAINT refund_requests_status_check CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT refund_requests_credits_check CHECK (credits > 0),
    -- A decided dispute must record who decided it. An approval with no
    -- reviewer is a refund nobody is accountable for.
    CONSTRAINT refund_requests_decided_has_reviewer CHECK (
        status = 'PENDING' OR reviewed_by IS NOT NULL)
);

-- One dispute per unlock. Without this a tutor could raise the same complaint
-- repeatedly and be refunded more than they were charged.
CREATE UNIQUE INDEX refund_requests_one_per_unlock_idx ON refund_requests (unlock_id);

-- The admin queue. Partial, because PENDING is a small slice of the table and
-- the only slice anyone queries interactively.
CREATE INDEX refund_requests_pending_idx ON refund_requests (created_at)
    WHERE status = 'PENDING';

-- Drives the dispute-rate abuse signal.
CREATE INDEX refund_requests_tutor_idx ON refund_requests (tutor_id, status);

CREATE TRIGGER trg_refund_requests_updated_at
    BEFORE UPDATE ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN refund_requests.credits IS
    'Copied from the unlock at dispute time. A repricing must never change what is refunded.';
