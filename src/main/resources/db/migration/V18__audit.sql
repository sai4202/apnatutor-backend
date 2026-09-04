-- V18 — Audit log (M5-08)
--
-- Who did what, to whom, and what it changed.
--
-- NOTE ON NUMBERING: TASKS.md originally reserved V17 for this. V17 went to
-- admin_moderation, which M5-05 needed first and which this table records
-- against; the audit log is V18. TASKS.md has been corrected.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE audit_log (
    id             BIGSERIAL   PRIMARY KEY,

    -- Nullable: a token whose claims we cannot read leaves no actor, and a
    -- deleted admin's decisions must survive them (M5-10.3 anonymises rather
    -- than removes, but ON DELETE SET NULL is the honest belt-and-braces).
    --
    -- A request with no credentials at all never gets this far: the security
    -- filter chain rejects it before the dispatcher, so it is not recorded
    -- here. See AuditInterceptor.
    actor_id       BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    actor_role     VARCHAR(16),

    -- A stable code where the service knew what it was doing (USER_SUSPENDED),
    -- otherwise derived from the route. Codes are for counting; the route is
    -- what makes an unrecognised entry still readable.
    action         VARCHAR(64) NOT NULL,

    target_type    VARCHAR(32),
    target_id      BIGINT,

    -- One human line. The state columns say what changed; this says what it was
    -- for, in the words the admin typed — the suspension reason, the removal
    -- reason, the decision note.
    summary        VARCHAR(500),

    -- Before/after (M5-08.2). JSONB rather than TEXT: these are queried when
    -- somebody asks "when did this tutor's fee change", and a JSON string column
    -- makes that a substring search.
    --
    -- Deliberately partial. A full row snapshot of every entity would put phone
    -- numbers and ID document keys into a table nobody ever deletes from; each
    -- call site sends the fields its decision actually moved.
    before_state   JSONB,
    after_state    JSONB,

    -- REFUSED entries are the reason this table is written from the HTTP layer
    -- rather than only from services. A run of refusals on /admin is somebody
    -- probing, and a log that only records what succeeded cannot show it.
    outcome        VARCHAR(16) NOT NULL,

    http_method    VARCHAR(8),
    path           VARCHAR(300),
    http_status    INTEGER,

    -- Ties an entry to its request's log lines, via CorrelationIdFilter.
    correlation_id VARCHAR(64),
    -- 45 chars: an IPv4-mapped IPv6 address is the longest form.
    ip_address     VARCHAR(45),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT audit_log_outcome_check CHECK (
        outcome IN ('SUCCEEDED', 'REFUSED', 'FAILED'))
);

-- The default view: everything, newest first.
CREATE INDEX audit_log_created_idx ON audit_log (created_at DESC);

-- "What has this admin been doing?"
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, created_at DESC);

-- "What has been done to this account / enquiry / review?" — the question asked
-- when a user complains, and the reason target_type is stored rather than
-- inferred from the path.
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id, created_at DESC);

-- Immutable (M5-08.4) ---------------------------------------------------------
--
-- Same enforcement as credit_transactions (V10): a trigger, not a convention.
-- An audit log that the application can edit is an audit log that proves
-- nothing — the first thing anyone covering their tracks would reach for is an
-- UPDATE, and "we only ever insert" is a claim about code, not about the data.
--
-- There is deliberately no retention job. When one is needed it must be a
-- documented policy with its own migration, not a DELETE somebody adds quietly.
CREATE OR REPLACE FUNCTION audit_log_is_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log is append-only (M5-08.4). An entry that can be edited '
        'proves nothing. Record a correcting entry instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();

COMMENT ON TABLE audit_log IS
    'Append-only. UPDATE and DELETE raise. Written from the HTTP layer for every '
    'admin mutation, enriched with before/after by the service that knows what changed.';

COMMENT ON COLUMN audit_log.outcome IS
    'SUCCEEDED, REFUSED (4xx — includes probing) or FAILED (5xx).';
