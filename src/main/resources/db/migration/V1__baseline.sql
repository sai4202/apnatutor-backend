-- V1 — Baseline
--
-- Shared infrastructure every later migration builds on. No domain tables here.
-- Migrations are forward-only: never edit this file once it has been applied
-- anywhere. Write V2, V3, ... instead. (SOURCE_OF_TRUTH.md section 7)

-- Fuzzy text search for tutor names and subjects (M2 discovery).
-- Also created by scripts/db-setup.sql; repeated here so a database restored
-- by any other route still ends up correct.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Every table carries created_at / updated_at. Rather than relying on the
-- application to remember, the database maintains updated_at itself: it stays
-- honest even for manual SQL fixes and admin scripts.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION set_updated_at() IS
    'Trigger function: stamps updated_at on every UPDATE. Attach to each table with '
    'CREATE TRIGGER trg_<table>_updated_at BEFORE UPDATE ON <table> '
    'FOR EACH ROW EXECUTE FUNCTION set_updated_at();';
