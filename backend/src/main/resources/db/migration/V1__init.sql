-- =====================================================================
-- V1 -- Core domain: users, refresh tokens, tickets, ticket history.
-- pgvector / embeddings live in V2 so the vector schema can evolve
-- independently of the relational core.
-- =====================================================================

-- Postgres 13+ ships gen_random_uuid() in core, no extension needed.
-- citext gives us case-insensitive email comparison without a manual
-- LOWER() index, and it works transparently with JPA String mapping.
CREATE EXTENSION IF NOT EXISTS citext;

-- ---------------------------------------------------------------------
-- users
-- password_hash holds a BCrypt digest (60 chars, salt embedded). The
-- column is sized at 255 so the algorithm can be upgraded in place
-- (e.g. Argon2id) without a migration.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   CITEXT       NOT NULL UNIQUE,
    password_hash           VARCHAR(255) NOT NULL,
    display_name            VARCHAR(120) NOT NULL,
    role                    VARCHAR(32)  NOT NULL DEFAULT 'USER',
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts   INTEGER      NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    last_login_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT users_role_check
        CHECK (role IN ('USER', 'AGENT', 'ADMIN')),
    CONSTRAINT users_failed_attempts_nonneg
        CHECK (failed_login_attempts >= 0)
);

-- ---------------------------------------------------------------------
-- refresh_tokens
-- Tokens are stored ONLY as SHA-256 hashes; the raw token never lands
-- in Postgres. `replaced_by_id` records the rotation chain so a reused
-- token can be detected (signals theft) and the whole family revoked.
-- ---------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  UUID         REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    user_agent      VARCHAR(512),
    ip_address      INET
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_active_expiry
    ON refresh_tokens(expires_at)
    WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------
-- tickets
-- The triage fields (category, priority, suggested_resolution,
-- triaged_at) are nullable until the RAG service writes them back.
-- ---------------------------------------------------------------------
CREATE TABLE tickets (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    submitter_id            UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assignee_id             UUID         REFERENCES users(id) ON DELETE SET NULL,
    title                   VARCHAR(200) NOT NULL,
    description             TEXT         NOT NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    category                VARCHAR(64),
    priority                VARCHAR(16),
    suggested_resolution    TEXT,
    triaged_at              TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT tickets_status_check
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    CONSTRAINT tickets_priority_check
        CHECK (priority IS NULL OR priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT tickets_title_nonblank
        CHECK (length(btrim(title)) > 0),
    CONSTRAINT tickets_description_nonblank
        CHECK (length(btrim(description)) > 0)
);

CREATE INDEX idx_tickets_submitter_id ON tickets(submitter_id);
CREATE INDEX idx_tickets_assignee_id  ON tickets(assignee_id);
CREATE INDEX idx_tickets_status       ON tickets(status);
CREATE INDEX idx_tickets_created_at   ON tickets(created_at DESC);

-- ---------------------------------------------------------------------
-- ticket_history
-- Append-only audit log. payload is JSONB so heterogeneous events
-- (status changes, comments, triage results) can share the table
-- without a schema migration each time we add an event type.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_history (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id   UUID        NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    actor_id    UUID        REFERENCES users(id) ON DELETE SET NULL,
    event_type  VARCHAR(48) NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ticket_history_event_type_check
        CHECK (event_type IN (
            'CREATED', 'UPDATED', 'STATUS_CHANGED', 'ASSIGNED',
            'TRIAGED', 'COMMENT', 'RESOLVED', 'REOPENED'
        ))
);

CREATE INDEX idx_ticket_history_ticket_created
    ON ticket_history(ticket_id, created_at);

-- ---------------------------------------------------------------------
-- updated_at maintenance trigger
-- Centralised so application code can never forget to bump updated_at.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER tickets_updated_at
    BEFORE UPDATE ON tickets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
