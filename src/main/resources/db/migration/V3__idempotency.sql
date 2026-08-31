-- V3 — Idempotency keys (M1-01.6)
--
-- Lets a client safely retry a money-moving request. Without this, a timeout on
-- an unlock leaves the tutor unable to tell whether they were charged, and a
-- retry may charge them twice. Required by M3-07 (lead unlock) and M4-02
-- (payment order creation).
--
-- Forward-only: never edit this file once applied.

CREATE TABLE idempotency_keys (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint        VARCHAR(255) NOT NULL,
    -- SHA-256 of the request body. A replay with the SAME key but a DIFFERENT
    -- body is a client bug, not a retry, and must be rejected rather than
    -- silently returning the earlier unrelated result.
    -- VARCHAR rather than CHAR: CHAR space-pads, which is a hazard for a value
    -- compared for exact equality, and Hibernate maps String to VARCHAR.
    request_hash    VARCHAR(64)  NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT idempotency_keys_state_check CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Scoped per user so one client's key cannot collide with, or read, another's.
-- This unique index is the actual concurrency control: two simultaneous requests
-- with the same key race to insert, and exactly one wins.
CREATE UNIQUE INDEX idempotency_keys_key_user_idx
    ON idempotency_keys (idempotency_key, user_id);

-- Supports the cleanup job that removes expired records.
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);

CREATE TRIGGER trg_idempotency_keys_updated_at
    BEFORE UPDATE ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE idempotency_keys IS
    'Replay protection for money-moving endpoints. A repeated (key, user) returns the stored response instead of acting again.';
