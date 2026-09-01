-- V15 — Tracking which grants have been through expiry
--
-- The first attempt at this recorded a zero-amount EXPIRY entry against a grant
-- that had been fully spent before it lapsed, purely to stop the expiry job
-- picking it up again on every future run. The database refused it, correctly:
-- credit_transactions_amount_nonzero exists because a zero-amount entry is not
-- a movement of money and would only pollute a history whose whole purpose is
-- to record movements.
--
-- The constraint was right and the design was wrong. "Which grants have been
-- through the expiry job" is bookkeeping about the ledger, not an entry in it,
-- so it lives in its own table. The ledger keeps only real movements, and the
-- job still terminates.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE credit_grant_expiries (
    -- The grant this records. Primary key, so a grant can be processed once and
    -- the job is idempotent by construction rather than by careful coding.
    credit_txn_id   BIGINT      PRIMARY KEY REFERENCES credit_transactions (id) ON DELETE CASCADE,

    tutor_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- How much was actually written off. Zero is meaningful here, unlike in the
    -- ledger: it means the tutor spent the credits before they lapsed, which is
    -- the outcome everyone wanted and is worth being able to count.
    credits_expired INTEGER     NOT NULL,

    -- The compensating ledger entry, when there was one. Null when nothing was
    -- written off.
    expiry_txn_id   BIGINT      REFERENCES credit_transactions (id),

    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT credit_grant_expiries_credits_check CHECK (credits_expired >= 0),
    -- A write-off must point at the entry that performed it, and a no-op must
    -- not claim one.
    CONSTRAINT credit_grant_expiries_txn_matches_amount CHECK (
        (credits_expired = 0 AND expiry_txn_id IS NULL)
        OR (credits_expired > 0 AND expiry_txn_id IS NOT NULL))
);

CREATE INDEX credit_grant_expiries_tutor_idx ON credit_grant_expiries (tutor_id);

COMMENT ON TABLE credit_grant_expiries IS
    'Bookkeeping about the ledger, not part of it. Records which grants the expiry job has already processed so it terminates.';
