-- ApnaTutor — local database bootstrap
--
-- Creates the application role and the dev + test databases.
-- Idempotent: safe to run repeatedly.
--
-- Run as the postgres superuser:
--   psql -U postgres -f scripts/db-setup.sql
--
-- The password here is for LOCAL DEVELOPMENT ONLY. Production credentials
-- come from environment variables and are never committed. See SOURCE_OF_TRUTH.md §7.

-- Application role -----------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'apnatutor') THEN
        CREATE ROLE apnatutor WITH LOGIN PASSWORD 'apnatutor';
        RAISE NOTICE 'Created role: apnatutor';
    ELSE
        RAISE NOTICE 'Role already exists: apnatutor';
    END IF;
END
$$;

-- Databases ------------------------------------------------------------------
-- CREATE DATABASE cannot run inside a transaction block or a DO block, so we
-- generate the statements and let psql's \gexec execute them.

SELECT 'CREATE DATABASE apnatutor_dev OWNER apnatutor ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'apnatutor_dev')
\gexec

SELECT 'CREATE DATABASE apnatutor_test OWNER apnatutor ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'apnatutor_test')
\gexec

-- Extensions -----------------------------------------------------------------
-- pg_trgm powers fuzzy tutor/subject search (SOURCE_OF_TRUTH.md M2 search indexes).
-- Must be installed per-database, by a superuser.

\connect apnatutor_dev
CREATE EXTENSION IF NOT EXISTS pg_trgm;
GRANT ALL ON SCHEMA public TO apnatutor;

\connect apnatutor_test
CREATE EXTENSION IF NOT EXISTS pg_trgm;
GRANT ALL ON SCHEMA public TO apnatutor;

\echo ''
\echo 'ApnaTutor databases ready: apnatutor_dev, apnatutor_test (owner: apnatutor)'
