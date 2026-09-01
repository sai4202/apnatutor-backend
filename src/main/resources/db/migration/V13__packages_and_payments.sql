-- V13 — Credit packages, payments and webhook events
--
-- Where money actually enters the business. Everything here exists to make one
-- claim defensible: credits were granted exactly once, for a payment that a
-- payment provider confirmed, and we can prove it months later.
--
-- Forward-only: never edit this file once applied.

-- Packages -------------------------------------------------------------------
-- Rows, not seed constants, for the same reason the pricing bands are rows: the
-- prices are a hypothesis. PENDING.md D3 and D4 are open questions, and a
-- repricing that needs a release is a repricing that does not happen.
CREATE TABLE credit_packages (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(60)  NOT NULL,
    credits      INTEGER      NOT NULL,

    -- Money in paise as BIGINT, never floating point (SOURCE_OF_TRUTH.md §5).
    price_paise  BIGINT       NOT NULL,

    -- Purely presentational: the "most popular" badge on the pricing page. Kept
    -- as data rather than hardcoded in the UI so marketing can move it.
    highlighted  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order   INTEGER      NOT NULL DEFAULT 0,

    -- Retired rather than deleted. A package someone has already bought must
    -- stay resolvable, or every historical payment loses the thing it paid for.
    active       BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT credit_packages_credits_check CHECK (credits > 0 AND credits <= 100000),
    -- A zero-price package would be free credits behind a checkout button.
    CONSTRAINT credit_packages_price_check CHECK (price_paise > 0)
);

CREATE INDEX credit_packages_active_idx ON credit_packages (active, sort_order);

CREATE TRIGGER trg_credit_packages_updated_at
    BEFORE UPDATE ON credit_packages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Starting ladder, at the SOURCE_OF_TRUTH.md §3.1 accounting value of Rs 10 per
-- credit, with a discount that grows with size. Unvalidated: see PENDING.md D3.
INSERT INTO credit_packages (name, credits, price_paise, highlighted, sort_order) VALUES
    ('Starter',  25,  25000,  FALSE, 1),   -- Rs 250,  Rs 10.00/credit
    ('Regular',  60,  55000,  TRUE,  2),   -- Rs 550,  Rs  9.17/credit
    ('Pro',     150, 125000,  FALSE, 3),   -- Rs 1250, Rs  8.33/credit
    ('Studio',  400, 300000,  FALSE, 4);   -- Rs 3000, Rs  7.50/credit

-- Payments -------------------------------------------------------------------
CREATE TABLE payments (
    id                 BIGSERIAL    PRIMARY KEY,
    tutor_id           BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    package_id         BIGINT       NOT NULL REFERENCES credit_packages (id),

    -- Copied from the package at order time, never read back through the FK.
    -- A package repriced next month must not rewrite what someone paid today.
    credits            INTEGER      NOT NULL,
    amount_paise       BIGINT       NOT NULL,
    currency           VARCHAR(3)   NOT NULL DEFAULT 'INR',

    status             VARCHAR(16)  NOT NULL DEFAULT 'CREATED',

    -- Razorpay's identifiers. The order id is ours to create; the payment id
    -- arrives with the confirmation.
    provider           VARCHAR(20)  NOT NULL DEFAULT 'RAZORPAY',
    provider_order_id  VARCHAR(80),
    provider_payment_id VARCHAR(80),

    -- Set when credits are actually granted, and the thing that makes granting
    -- idempotent: a second confirmation for the same payment finds this
    -- populated and does nothing.
    credited_at        TIMESTAMPTZ,
    credit_txn_id      BIGINT       REFERENCES credit_transactions (id),

    failure_reason     VARCHAR(300),

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payments_status_check CHECK (
        status IN ('CREATED', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT payments_amount_check CHECK (amount_paise > 0),
    CONSTRAINT payments_credits_check CHECK (credits > 0),
    -- A paid payment must carry the provider's payment id. Without it there is
    -- nothing to reconcile against the provider's own records.
    CONSTRAINT payments_paid_has_provider_id CHECK (
        status <> 'PAID' OR provider_payment_id IS NOT NULL)
);

-- One payment per provider order. This is the constraint that stops a duplicate
-- webhook delivery from creating a second payment row for the same purchase.
CREATE UNIQUE INDEX payments_provider_order_idx
    ON payments (provider, provider_order_id)
    WHERE provider_order_id IS NOT NULL;

-- The same for the payment id, which is what the webhook actually carries.
CREATE UNIQUE INDEX payments_provider_payment_idx
    ON payments (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

CREATE INDEX payments_tutor_idx ON payments (tutor_id, created_at DESC);

-- Finds orders that were created but never confirmed, for the reconciliation
-- job. Partial, because CREATED is a small and shrinking slice of the table.
CREATE INDEX payments_pending_idx ON payments (created_at)
    WHERE status = 'CREATED';

CREATE TRIGGER trg_payments_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Webhook events -------------------------------------------------------------
-- Every delivery is recorded before it is acted on, raw body included.
--
-- Two reasons, both learned the expensive way by everyone who has skipped it.
-- Disputes: when a tutor says they paid and we say they did not, the provider's
-- own signed payload is the only account either side trusts. And debugging: a
-- webhook that was mishandled is unreproducible unless the exact bytes were
-- kept, because the provider will not send it again on request.
CREATE TABLE payment_webhook_events (
    id             BIGSERIAL    PRIMARY KEY,

    -- The provider's event id. Unique, and that uniqueness is the whole
    -- idempotency mechanism: three deliveries of one event insert once.
    event_id       VARCHAR(120) NOT NULL,
    provider       VARCHAR(20)  NOT NULL DEFAULT 'RAZORPAY',
    event_type     VARCHAR(60)  NOT NULL,

    payment_id     BIGINT       REFERENCES payments (id) ON DELETE SET NULL,

    signature_valid BOOLEAN     NOT NULL,

    -- The exact bytes received. JSONB rather than TEXT so it can be queried
    -- during an investigation without being reparsed by hand.
    payload        JSONB        NOT NULL,

    processed_at   TIMESTAMPTZ,
    processing_error VARCHAR(500),

    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX payment_webhook_events_id_idx
    ON payment_webhook_events (provider, event_id);

CREATE INDEX payment_webhook_events_unprocessed_idx
    ON payment_webhook_events (received_at)
    WHERE processed_at IS NULL;

COMMENT ON TABLE payment_webhook_events IS
    'Append-only in practice: rows are never edited except to stamp processed_at. Keep the raw payload — it is the only evidence in a payment dispute.';

-- Notification types for the money flow --------------------------------------
-- The CHECK is rewritten rather than extended; Postgres has no "add value to a
-- check constraint", and the full list in one place is easier to read against
-- NotificationType.java than a chain of amendments would be.
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
    'REFUND_REJECTED'));

-- Signup bonus, granted at most once -----------------------------------------
--
-- SOURCE_OF_TRUTH.md §3.3. Application logic alone is not enough here: two
-- verification approvals landing together would each check "no bonus yet",
-- each find none, and each grant. A partial unique index makes the database
-- refuse the second, whatever the application believes.
CREATE UNIQUE INDEX credit_transactions_one_signup_bonus_idx
    ON credit_transactions (tutor_id)
    WHERE reason = 'SIGNUP_BONUS';
