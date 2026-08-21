-- The role the deployed agent connects as.
--
-- Two independent reasons this is read-only, and either alone would justify it
-- (docs/DOMAIN.md decision 10):
--
-- 1. Fineract's ~40 _derived rollup columns are maintained by its Java write
--    path, which we are not running. Nothing recomputes or repairs them. A
--    single stray UPDATE leaves the database permanently inconsistent, with no
--    process that would ever notice, and every answer after that is wrong.
--
-- 2. The service builds queries from LLM output. SQL injection and prompt
--    injection converge on the same surface, so the blast radius should be
--    bounded by the database, not only by application code.
--
-- Run as the database owner, against the agent database:
--   psql "$AGENT_DB_URL" -v ON_ERROR_STOP=1 -f scripts/stage6/create-readonly-user.sql
--
-- Then set AGENT_DB_USER / AGENT_DB_PASSWORD to this role for the deployment.
-- Flyway must keep running as the OWNER: migrations create tables, and a
-- read-only role cannot. That split is deliberate - deploy-time writes are
-- authorised, request-time writes are not.

\set ON_ERROR_STOP on

-- Replace before running. Neon shows the value once; store it as a Cloud Run
-- secret rather than in this file.
\set agent_password 'CHANGE-ME'

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_ro') THEN
        CREATE ROLE agent_ro LOGIN;
    END IF;
END $$;

ALTER ROLE agent_ro WITH PASSWORD :'agent_password';

-- Belt and braces: even if a GRANT is missed, or a future migration adds a
-- table whose default privileges are wrong, this role cannot write.
ALTER ROLE agent_ro SET default_transaction_read_only = on;

-- Neon roles can have an empty search_path, which is why Flyway needed an
-- explicit default-schema. Set it here so the agent's SQL does not have to
-- schema-qualify every table.
ALTER ROLE agent_ro SET search_path = public;

GRANT CONNECT ON DATABASE CURRENT_DATABASE TO agent_ro;
GRANT USAGE ON SCHEMA public TO agent_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO agent_ro;

-- Tables created by later migrations are covered without re-running this.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO agent_ro;

-- Verify. Both should be denied.
--   psql "postgresql://agent_ro:...@host/fineract_agent" \
--     -c "insert into m_office(id,name,opening_date) values (99,'x',date '2026-01-01')"
--   psql "postgresql://agent_ro:...@host/fineract_agent" \
--     -c "update m_loan set principal_amount = 0"
