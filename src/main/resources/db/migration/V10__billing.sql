-- V10 — Credit wallet and ledger
--
-- SOURCE_OF_TRUTH.md Invariant 1: credit_transactions is APPEND-ONLY. The ledger
-- is the authority on how many credits anyone has; credit_wallets.balance is a
-- cache that must be reconcilable by replaying it.
--
-- Moved into M3 from M4 because the unlock endpoint spends credits and cannot be
-- built or tested without a wallet. M4 adds the money coming IN — Razorpay,
-- packages, invoices.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE credit_wallets (
    id         BIGSERIAL   PRIMARY KEY,
    -- The tutor's user id. Billing is an account-level concern.
    tutor_id   BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,

    -- A CACHE, not the truth. Written in the same transaction as the ledger
    -- entry that changes it. If this and the ledger ever disagree, the ledger is
    -- right and this is rebuilt from it.
    balance    INTEGER     NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A balance cannot go negative. If a bug ever tries to spend more than a
    -- tutor has, the transaction aborts rather than quietly creating credit out
    -- of nothing.
    CONSTRAINT credit_wallets_balance_non_negative CHECK (balance >= 0)
);

CREATE TRIGGER trg_credit_wallets_updated_at
    BEFORE UPDATE ON credit_wallets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN credit_wallets.balance IS
    'CACHE of the ledger, not the authority. Reconcilable by replaying credit_transactions.';

-- The ledger ---------------------------------------------------------------------
CREATE TABLE credit_transactions (
    id             BIGSERIAL   PRIMARY KEY,
    tutor_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Signed. Positive is credit granted, negative is credit spent. One column
    -- rather than separate debit/credit columns, so the balance is a plain SUM
    -- and no row can be ambiguous about its direction.
    amount         INTEGER     NOT NULL,

    reason         VARCHAR(24) NOT NULL,
    reference_type VARCHAR(32),
    reference_id   BIGINT,

    -- Purchased credits last 365 days, bonus credits 90 (SoT §3.4).
    expires_at     TIMESTAMPTZ,

    -- The balance immediately after this entry. Redundant with a running SUM,
    -- and worth it: a support question about "why is my balance 12" is answered
    -- by reading one row rather than replaying a year of history.
    balance_after  INTEGER     NOT NULL,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT credit_transactions_reason_check CHECK (
        reason IN ('PURCHASE', 'SIGNUP_BONUS', 'UNLOCK', 'REFUND', 'ADMIN_ADJUSTMENT', 'EXPIRY')),
    -- A zero-amount entry says nothing and would only pollute the history.
    CONSTRAINT credit_transactions_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT credit_transactions_balance_non_negative CHECK (balance_after >= 0)
);

CREATE INDEX credit_transactions_tutor_idx ON credit_transactions (tutor_id, created_at DESC);
CREATE INDEX credit_transactions_expiry_idx
    ON credit_transactions (expires_at)
    WHERE expires_at IS NOT NULL AND amount > 0;

-- APPEND-ONLY, ENFORCED BY THE DATABASE ------------------------------------------
--
-- SOURCE_OF_TRUTH.md Invariant 1 says the ledger is insert-only. A comment saying
-- so is not a control: the first person under deadline pressure who needs to
-- "just fix" a balance will write an UPDATE, and financial history that can be
-- edited is not history.
--
-- This raises instead. Correcting a mistake means posting a compensating entry
-- with reason ADMIN_ADJUSTMENT, which is what a ledger is for — the error and
-- its correction both remain visible.
CREATE OR REPLACE FUNCTION credit_transactions_are_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'credit_transactions is append-only (SOURCE_OF_TRUTH.md Invariant 1). '
        'To correct a balance, insert a compensating entry with reason ADMIN_ADJUSTMENT.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_credit_transactions_no_update
    BEFORE UPDATE ON credit_transactions
    FOR EACH ROW EXECUTE FUNCTION credit_transactions_are_append_only();

CREATE TRIGGER trg_credit_transactions_no_delete
    BEFORE DELETE ON credit_transactions
    FOR EACH ROW EXECUTE FUNCTION credit_transactions_are_append_only();

COMMENT ON TABLE credit_transactions IS
    'Append-only ledger. UPDATE and DELETE raise. Correct with a compensating ADMIN_ADJUSTMENT entry.';
