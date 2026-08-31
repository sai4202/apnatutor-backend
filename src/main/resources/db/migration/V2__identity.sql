-- V2 — Identity
--
-- Accounts, one-time passcodes, and refresh tokens.
-- Forward-only: never edit this file once applied. (SOURCE_OF_TRUTH.md section 7)

-- Accounts --------------------------------------------------------------------
-- Phone is the login identity (ADR #9): Indian consumers expect phone+OTP, and
-- email-first signup suppresses conversion badly in this market. Email is
-- optional, and password_hash is nullable because OTP-only accounts are normal.
CREATE TABLE users (
    id                BIGSERIAL PRIMARY KEY,
    phone             VARCHAR(16)  NOT NULL,
    email             VARCHAR(255),
    password_hash     VARCHAR(72),
    role              VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    phone_verified_at TIMESTAMPTZ,
    email_verified_at TIMESTAMPTZ,
    last_active_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Enum values are constrained here as well as in Java. The database is the
    -- last line of defence against a bad manual UPDATE or a future bug.
    CONSTRAINT users_role_check   CHECK (role IN ('STUDENT', 'TUTOR', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    -- E.164, stored canonically (+919876543210). Normalising on the way in means
    -- one phone number cannot become two accounts through formatting differences.
    CONSTRAINT users_phone_e164   CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE UNIQUE INDEX users_phone_key ON users (phone);

-- Case-insensitive and partial: two accounts must not differ only by capitalisation,
-- but any number of accounts may have no email at all.
CREATE UNIQUE INDEX users_email_key ON users (lower(email)) WHERE email IS NOT NULL;

CREATE INDEX users_role_status_idx ON users (role, status);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN users.phone IS 'E.164, canonical form. The login identity.';
COMMENT ON COLUMN users.password_hash IS 'Nullable: OTP-only accounts have no password.';

-- One-time passcodes ----------------------------------------------------------
-- Keyed by phone, not user_id: a code is sent before we know whether an account
-- exists, and the response must not reveal which (enumeration defence, M1-03.7).
--
-- The code itself is never stored, only a BCrypt hash. A leaked database must not
-- hand an attacker live login codes.
CREATE TABLE otp_codes (
    id          BIGSERIAL PRIMARY KEY,
    phone       VARCHAR(16) NOT NULL,
    code_hash   VARCHAR(72) NOT NULL,
    purpose     VARCHAR(24) NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT otp_codes_purpose_check  CHECK (purpose IN ('AUTH')),
    CONSTRAINT otp_codes_attempts_check CHECK (attempts >= 0)
);

-- Serves both "latest live code for this phone" and the hourly send-rate count.
CREATE INDEX otp_codes_phone_created_idx ON otp_codes (phone, created_at DESC);

CREATE TRIGGER trg_otp_codes_updated_at
    BEFORE UPDATE ON otp_codes
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Refresh tokens --------------------------------------------------------------
-- Stored as a SHA-256 hex hash, never in the clear: possession of the raw token
-- IS the credential, so a database leak must not be a session leak. SHA-256
-- rather than BCrypt because the token is already 256 bits of entropy — there is
-- nothing to brute-force, and refresh happens on a hot path.
--
-- family_id enables reuse detection: rotation issues a new token in the same
-- family, so if an already-rotated token is presented again the token was
-- stolen, and the whole family is revoked.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- VARCHAR, not CHAR: CHAR space-pads to its declared width, which is a quiet
    -- hazard for a value compared for exact equality. Hibernate also maps a String
    -- field to VARCHAR, so CHAR here fails ddl-auto: validate.
    token_hash VARCHAR(64) NOT NULL,
    family_id  UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX refresh_tokens_hash_key ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TRIGGER trg_refresh_tokens_updated_at
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hex (64 chars) of the raw token. The raw value is never stored.';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Rotation lineage. Reuse of a rotated token revokes the whole family.';
