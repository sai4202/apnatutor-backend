-- V11 — Notifications
--
-- A record of what we told someone and when. Kept even after delivery, because
-- "did you tell me about this?" is a real support question and a log line that
-- has rotated away cannot answer it.
--
-- Forward-only: never edit this file once applied.

CREATE TABLE notifications (
    id            BIGSERIAL   PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type          VARCHAR(40) NOT NULL,

    title         VARCHAR(200) NOT NULL,
    body          VARCHAR(1000) NOT NULL,

    -- What this is about, so a notification can link somewhere useful.
    reference_type VARCHAR(32),
    reference_id   BIGINT,

    -- Which channels actually went out. Nullable because a notification is
    -- recorded first and delivered after — see NotificationService: sending
    -- inside the unlock transaction would let an SMS failure roll back a paid
    -- unlock.
    sent_channels VARCHAR(60),

    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notifications_type_check CHECK (type IN (
        'NEW_MATCHING_LEAD',
        'TUTOR_RESPONDED',
        'LOW_CREDIT_BALANCE',
        'VERIFICATION_APPROVED',
        'VERIFICATION_REJECTED',
        'REQUIREMENT_EXPIRING'))
);

-- The in-app notification list: newest first, per user.
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);

-- The unread badge.
CREATE INDEX notifications_unread_idx
    ON notifications (user_id)
    WHERE read_at IS NULL;

CREATE TRIGGER trg_notifications_updated_at
    BEFORE UPDATE ON notifications
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
