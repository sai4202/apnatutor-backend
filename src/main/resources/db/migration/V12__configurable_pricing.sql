-- V12 — Admin-configurable pricing and platform settings
--
-- Moves the SOURCE_OF_TRUTH.md §3 numbers out of Java constants and into the
-- database, where an admin can change them without a deploy.
--
-- Why this matters beyond convenience: those numbers were a hypothesis. Credit
-- prices, the unlock cap and the online discount all need tuning against real
-- tutor behaviour, and a pricing change that requires a release is a pricing
-- change that does not happen.
--
-- Forward-only: never edit this file once applied.

-- Typed key/value settings ---------------------------------------------------
-- A table rather than more columns, because these are unrelated scalars that
-- change independently. The type column exists so a value can be validated on
-- write instead of blowing up at the point of use.
CREATE TABLE platform_settings (
    key         VARCHAR(64)  PRIMARY KEY,
    value       VARCHAR(200) NOT NULL,
    value_type  VARCHAR(16)  NOT NULL,
    description VARCHAR(500) NOT NULL,
    min_value   NUMERIC(12, 4),
    max_value   NUMERIC(12, 4),
    updated_by  BIGINT       REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT platform_settings_type_check CHECK (value_type IN ('INT', 'DECIMAL', 'BOOLEAN'))
);

CREATE TRIGGER trg_platform_settings_updated_at
    BEFORE UPDATE ON platform_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Bounds are stored alongside each setting so the admin UI can enforce them and
-- the service can reject a value that would break the product. An unlock cap of
-- 500 is not a configuration choice, it is an outage for every parent who posts.
INSERT INTO platform_settings (key, value, value_type, description, min_value, max_value) VALUES
    ('lead.unlock_cap', '5', 'INT',
     'Maximum tutors who may unlock one requirement. Raising this means more calls to each parent.',
     1, 20),

    ('lead.online_multiplier', '0.8', 'DECIMAL',
     'Price multiplier for online-only enquiries. Rounded up, so a lead is never free.',
     0.1, 1.0),

    ('credits.value_paise', '1000', 'DECIMAL',
     'Internal accounting value of one credit, in paise. Currently 1000 = Rs 10.',
     100, 100000),

    ('credits.signup_bonus', '10', 'INT',
     'Credits granted once when a tutor reaches ID_VERIFIED.',
     0, 200),

    ('credits.purchased_validity_days', '365', 'INT',
     'How long purchased credits last.', 30, 3650),

    ('credits.bonus_validity_days', '90', 'INT',
     'How long bonus credits last.', 7, 3650),

    ('credits.low_balance_threshold', '10', 'INT',
     'Below this a tutor is warned they will soon start missing leads.', 0, 500),

    ('requirements.lifetime_days', '30', 'INT',
     'How long a requirement stays open before expiring.', 1, 365),

    ('refunds.window_days', '7', 'INT',
     'How long a tutor has to dispute a bad lead.', 1, 90)
ON CONFLICT (key) DO NOTHING;

-- Lead pricing bands ---------------------------------------------------------
-- Rows rather than a hardcoded ladder, so an admin can add, remove or reprice a
-- band. Each row is a lower bound; the applicable band is the highest whose
-- bound the budget meets.
CREATE TABLE lead_pricing_bands (
    id               BIGSERIAL   PRIMARY KEY,
    -- Inclusive lower bound in paise. The lowest band must be 0 so every budget
    -- matches something.
    min_budget_paise BIGINT      NOT NULL,
    credits          INTEGER     NOT NULL,
    label            VARCHAR(60) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT lead_pricing_bands_bound_check CHECK (min_budget_paise >= 0),
    -- A zero-credit band would be a free contact reveal: the business model
    -- given away by a configuration change.
    CONSTRAINT lead_pricing_bands_credits_check CHECK (credits > 0 AND credits <= 1000)
);

-- One band per bound. Two bands starting at the same budget would make pricing
-- depend on row order, which is not a decision anyone made.
CREATE UNIQUE INDEX lead_pricing_bands_bound_idx ON lead_pricing_bands (min_budget_paise);

CREATE TRIGGER trg_lead_pricing_bands_updated_at
    BEFORE UPDATE ON lead_pricing_bands
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The SOURCE_OF_TRUTH.md §3.1 ladder as the starting point. These are a
-- hypothesis, not researched figures, and are now editable without a deploy.
INSERT INTO lead_pricing_bands (min_budget_paise, credits, label) VALUES
    (0,       3,  'Under Rs 2,000'),
    (200000,  5,  'Rs 2,000 - 4,999'),
    (500000,  8,  'Rs 5,000 - 9,999'),
    (1000000, 12, 'Rs 10,000 and above')
ON CONFLICT (min_budget_paise) DO NOTHING;

-- Lock the cap onto each requirement, like the price -------------------------
--
-- Without this, raising the cap from 5 to 7 would silently reopen every capped
-- requirement on the platform, sending two more tutors after parents who were
-- told to expect at most five calls. Locking it at creation means a change
-- applies to new enquiries only, which is the same rule the price already
-- follows and the only one that keeps a promise made to a parent.
ALTER TABLE requirements
    ADD COLUMN unlock_cap INTEGER NOT NULL DEFAULT 5;

ALTER TABLE requirements
    ADD CONSTRAINT requirements_unlock_cap_check CHECK (unlock_cap BETWEEN 1 AND 20);

COMMENT ON COLUMN requirements.unlock_cap IS
    'Locked at creation, like unlock_cost_credits. A later config change never moves a promise already made.';
