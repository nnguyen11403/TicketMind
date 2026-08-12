"""Async Postgres connection pool + schema bootstrap.

The service owns its own ``kb_documents`` table rather than reading the
backend's ``ticket_embeddings``. Two reasons:

- **Blast radius.** A schema mistake here can't corrupt the ticket data the
  backend depends on for auth / audit trails.
- **Ownership.** Flyway lives with the Java service. Cross-service migrations
  through Flyway would couple deploys; a self-owned table keeps the RAG
  deploy independent.

The table is created on first startup with ``IF NOT EXISTS`` so re-running
against an already-provisioned database is a no-op.
"""

from __future__ import annotations

from psycopg_pool import AsyncConnectionPool

_KB_DOCUMENTS_DDL = """
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_documents (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id  VARCHAR(128) NOT NULL UNIQUE,
    title        VARCHAR(256) NOT NULL,
    body         TEXT         NOT NULL,
    category     VARCHAR(64),
    embedding    vector({dim}) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kb_documents_vector_cosine
    ON kb_documents
    USING hnsw (embedding vector_cosine_ops);
"""


async def build_pool(database_url: str) -> AsyncConnectionPool:
    """Return an opened :class:`AsyncConnectionPool` for ``database_url``."""

    pool = AsyncConnectionPool(
        conninfo=database_url,
        min_size=1,
        max_size=8,
        open=False,
    )
    await pool.open()
    return pool


async def ensure_schema(pool: AsyncConnectionPool, embedding_dim: int) -> None:
    """Create ``kb_documents`` and its HNSW index if they aren't there yet.

    ``embedding_dim`` is templated into the DDL so switching models later
    (e.g. voyage-3 → voyage-3-large) is a config change plus a re-embed.
    """

    ddl = _KB_DOCUMENTS_DDL.format(dim=int(embedding_dim))
    async with pool.connection() as conn, conn.cursor() as cur:
        await cur.execute(ddl)
