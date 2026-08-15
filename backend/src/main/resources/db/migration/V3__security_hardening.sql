-- =====================================================================
-- V3 -- Least-privilege roles, row-level security, and the column type
-- changes that column-level encryption needs.
--
-- Runs as the owning role. RlsConfig suspends the runtime role switch for
-- the duration of the migration, because this file is what creates the role
-- every other connection switches into.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Widen the columns that now hold ciphertext.
--
-- The AES-GCM envelope (v1:<iv>:<ciphertext||tag>) is longer than the
-- plaintext it wraps, so VARCHAR(200) can no longer hold a 200-character
-- title. The user-visible limits still exist, they are enforced by the
-- request DTOs, which is where a length error can be reported as a field
-- error instead of a constraint violation.
--
-- ticket_history.payload leaves JSONB behind for the same reason. Nothing
-- ever queried it with JSONB operators: the audit log is read whole, by
-- ticket, and rendered client-side. The only capability given up is one
-- that was never used.
-- ---------------------------------------------------------------------
ALTER TABLE tickets ALTER COLUMN title TYPE text;

ALTER TABLE ticket_history ALTER COLUMN payload DROP DEFAULT;
ALTER TABLE ticket_history ALTER COLUMN payload TYPE text USING payload::text;
ALTER TABLE ticket_history ALTER COLUMN payload SET DEFAULT '{}';

-- ---------------------------------------------------------------------
-- 2. Roles.
--
-- Neither role owns a table and neither has BYPASSRLS, which is what makes
-- the policies below apply to them at all: a table's owner is exempt from
-- its own policies unless the table is FORCEd, and a superuser is exempt
-- unconditionally.
--
-- Passwords arrive as Flyway placeholders. Generate them with
-- `openssl rand -hex 32` — hex specifically, because the value is
-- interpolated into this file as a SQL literal and into the RAG service's
-- DATABASE_URL as a URI component.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketmind_app') THEN
        CREATE ROLE ticketmind_app;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketmind_rag') THEN
        CREATE ROLE ticketmind_rag;
    END IF;
END
$$;

ALTER ROLE ticketmind_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD '${app_db_password}';
ALTER ROLE ticketmind_rag WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD '${rag_db_password}';

-- The backend's role: DML on the domain tables, no DDL, no access to
-- Flyway's own bookkeeping.
GRANT USAGE ON SCHEMA public TO ticketmind_app;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON users, refresh_tokens, tickets, ticket_history, ticket_embeddings
    TO ticketmind_app;

-- The RAG service's role: it owns nothing here and must not be able to read
-- tickets or users. It gets CREATE on the schema only because it creates and
-- owns kb_documents itself at startup — see the note in rag-service/db.py on
-- why that table deliberately does not live in Flyway. This is the container
-- that talks to a third-party model, so it is the one that most needs to be
-- unable to read the rest of the database.
GRANT USAGE, CREATE ON SCHEMA public TO ticketmind_rag;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = 'kb_documents') THEN
        EXECUTE 'ALTER TABLE kb_documents OWNER TO ticketmind_rag';
    END IF;
END
$$;

-- ---------------------------------------------------------------------
-- 3. Request context.
--
-- RlsDataSource sets these on every connection it hands out, from the
-- authenticated principal. Work that runs before authentication (login,
-- refresh) or off a request thread entirely (async triage write-back, the
-- admin bootstrap) has no principal and runs as SYSTEM.
--
-- current_setting(..., true) returns NULL rather than erroring when the
-- variable was never set, so a connection that somehow escapes the
-- decorator gets no identity and every policy below denies it.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_current_user_id() RETURNS uuid
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
    AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION app_current_role() RETURNS text
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
    AS $$ SELECT COALESCE(NULLIF(current_setting('app.user_role', true), ''), 'ANONYMOUS') $$;

CREATE OR REPLACE FUNCTION app_is_system() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    AS $$ SELECT app_current_role() = 'SYSTEM' $$;

CREATE OR REPLACE FUNCTION app_is_staff() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    AS $$ SELECT app_current_role() IN ('AGENT', 'ADMIN', 'SYSTEM') $$;

-- ---------------------------------------------------------------------
-- 4. Policies.
--
-- These mirror the checks TicketService already performs in Java. That
-- duplication is the point: the Java check is what produces a correct 404,
-- the policy is what holds when a future query forgets to call it.
--
-- Multiple permissive policies on the same command are OR'd, so each rule
-- below is written as its own policy rather than one long expression.
-- ---------------------------------------------------------------------

-- DELETE is spelled out separately everywhere below rather than folded into
-- FOR ALL. A FOR ALL policy has no WITH CHECK path for DELETE, so USING
-- alone decides it: writing `FOR ALL USING (app_is_staff())` would hand
-- every agent the ability to delete rows the API never lets anyone delete.

ALTER TABLE tickets ENABLE ROW LEVEL SECURITY;

CREATE POLICY tickets_staff_read ON tickets FOR SELECT
    USING (app_is_staff());

CREATE POLICY tickets_submitter_read ON tickets FOR SELECT
    USING (submitter_id = app_current_user_id());

CREATE POLICY tickets_insert ON tickets FOR INSERT
    WITH CHECK (app_is_staff() OR submitter_id = app_current_user_id());

CREATE POLICY tickets_update ON tickets FOR UPDATE
    USING (app_is_staff() OR submitter_id = app_current_user_id())
    WITH CHECK (app_is_staff() OR submitter_id = app_current_user_id());

CREATE POLICY tickets_delete ON tickets FOR DELETE
    USING (app_is_system());

ALTER TABLE ticket_history ENABLE ROW LEVEL SECURITY;

-- The subquery is itself filtered by the tickets policies above, so a
-- submitter can only ever match a ticket they can already see.
CREATE POLICY ticket_history_staff_read ON ticket_history FOR SELECT
    USING (app_is_staff());

CREATE POLICY ticket_history_submitter_read ON ticket_history FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tickets t
        WHERE t.id = ticket_history.ticket_id
          AND t.submitter_id = app_current_user_id()));

CREATE POLICY ticket_history_insert ON ticket_history FOR INSERT
    WITH CHECK (app_is_staff() OR EXISTS (
        SELECT 1 FROM tickets t
        WHERE t.id = ticket_history.ticket_id
          AND t.submitter_id = app_current_user_id()));

-- The audit log is append-only. Nothing in the application updates a history
-- row, and nothing should be able to.
CREATE POLICY ticket_history_delete ON ticket_history FOR DELETE
    USING (app_is_system());

ALTER TABLE users ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_staff_read ON users FOR SELECT
    USING (app_is_staff());

CREATE POLICY users_self_read ON users FOR SELECT
    USING (id = app_current_user_id());

-- A submitter has to be able to render the agent handling their ticket, so
-- the counterparty on a shared ticket is readable — and nobody else is.
CREATE POLICY users_counterparty_read ON users FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM tickets t
        WHERE (t.assignee_id = users.id OR t.submitter_id = users.id)
          AND t.submitter_id = app_current_user_id()));

-- Role, lockout counters and the password hash all live on this row, so the
-- only writers are SYSTEM (registration and the login bookkeeping, both of
-- which run before anyone is authenticated) and ADMIN (role changes). There
-- is deliberately no self-UPDATE policy: a submitter cannot promote
-- themselves even with a working SQL injection.
CREATE POLICY users_insert ON users FOR INSERT
    WITH CHECK (app_is_system());

CREATE POLICY users_update ON users FOR UPDATE
    USING (app_is_system() OR app_current_role() = 'ADMIN')
    WITH CHECK (app_is_system() OR app_current_role() = 'ADMIN');

CREATE POLICY users_delete ON users FOR DELETE
    USING (app_is_system());

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY refresh_tokens_system ON refresh_tokens FOR ALL
    USING (app_is_system())
    WITH CHECK (app_is_system());

CREATE POLICY refresh_tokens_own ON refresh_tokens FOR ALL
    USING (user_id = app_current_user_id())
    WITH CHECK (user_id = app_current_user_id());

-- Written only by out-of-band embedding work, never in a user's request.
ALTER TABLE ticket_embeddings ENABLE ROW LEVEL SECURITY;

CREATE POLICY ticket_embeddings_system ON ticket_embeddings FOR ALL
    USING (app_is_system())
    WITH CHECK (app_is_system());
