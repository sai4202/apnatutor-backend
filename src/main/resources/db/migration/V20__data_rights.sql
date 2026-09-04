-- V20 — Data rights (M5-10)
--
-- Deletion under the DPDP Act. The whole design is in one sentence:
-- ANONYMISE, NEVER DELETE.
--
-- The financial ledger must survive a departed user. credit_transactions is
-- append-only (Invariant 1) and audit_log is append-only (M5-08.4), and both
-- reference users.id. Deleting the row would either cascade rows out of a
-- ledger that is supposed to be immutable, or leave dangling references in it.
-- So the row stays and its personal fields are emptied.
--
-- Forward-only: never edit this file once applied.

-- When the personal data was removed. Distinct from status = DELETED, which is
-- the state; this is the date the retention clock started, and it is what any
-- future question about statutory retention will be answered from.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE users ADD CONSTRAINT users_deleted_at_check CHECK (
    status <> 'DELETED' OR deleted_at IS NOT NULL);

COMMENT ON COLUMN users.deleted_at IS
    'When personal data was erased. The row itself is kept: financial and audit '
    'records reference it and are append-only (M5-10.3).';

-- The anonymised phone -------------------------------------------------------
--
-- users.phone is NOT NULL, is uniquely indexed, and is CHECKed against E.164.
-- An anonymised value therefore cannot be null, cannot be a constant, and
-- cannot be free text - three constraints that between them rule out most of
-- the obvious approaches.
--
-- The scheme is '+99' followed by the zero-padded user id: unique by
-- construction, valid E.164 by shape, and +99 is not an assigned country code,
-- so it can never collide with a real number. Applied by DataRightsService.
--
-- This index makes "how many accounts have been erased?" answerable without a
-- scan, and makes an accidental collision with a real number visible.
CREATE INDEX users_anonymised_idx ON users (deleted_at) WHERE deleted_at IS NOT NULL;
