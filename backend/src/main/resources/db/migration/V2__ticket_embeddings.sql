-- =====================================================================
-- V2 -- pgvector extension and embedding storage for the RAG pipeline.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- ticket_embeddings
-- Kept separate from `tickets` so embeddings can be re-generated
-- (model upgrades, drift fixes) without touching ticket rows, and so
-- one ticket can have multiple chunks. Dimension 1024 matches Voyage
-- voyage-3 -- the default chosen in .env.example. A migration will
-- introduce a parallel column if we add a second embedding model.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_embeddings (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id       UUID         NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    embedding       vector(1024) NOT NULL,
    embedding_model VARCHAR(64)  NOT NULL,
    chunk_index     INTEGER      NOT NULL DEFAULT 0,
    chunk_text      TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ticket_embeddings_chunk_index_nonneg
        CHECK (chunk_index >= 0),
    CONSTRAINT ticket_embeddings_unique_per_chunk
        UNIQUE (ticket_id, chunk_index, embedding_model)
);

CREATE INDEX idx_ticket_embeddings_ticket_id
    ON ticket_embeddings(ticket_id);

-- HNSW with cosine distance is the current pgvector default for
-- semantic search. m=16, ef_construction=64 are the library defaults;
-- revisit when ticket volume passes ~100k rows.
CREATE INDEX idx_ticket_embeddings_vector_cosine
    ON ticket_embeddings
    USING hnsw (embedding vector_cosine_ops);
