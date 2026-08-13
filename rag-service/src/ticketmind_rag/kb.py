"""Knowledge-base persistence + retrieval.

Upsert-by-external-id so the backend can idempotently re-post the same ticket
after edits; retrieval uses pgvector's cosine distance operator (``<=>``) so
the HNSW index built in :mod:`db` is actually used.
"""

from __future__ import annotations

from dataclasses import dataclass

from pgvector import Vector
from pgvector.psycopg import register_vector_async
from psycopg_pool import AsyncConnectionPool

from .embeddings import Embedder
from .schemas import KbDocumentIn, KbDocumentOut, KbSearchHit


@dataclass(frozen=True)
class KbDocumentRow:
    id: str
    external_id: str
    title: str
    body: str
    category: str | None
    score: float | None


class KbRepository:
    """Thin data-access layer around ``kb_documents``.

    Register-vector once per connection is handled inside each method, the
    async pool may hand back a fresh connection at any point.
    """

    def __init__(self, pool: AsyncConnectionPool, embedder: Embedder) -> None:
        self._pool = pool
        self._embedder = embedder

    async def upsert(self, doc: KbDocumentIn) -> KbDocumentOut:
        # Wrapping in Vector is what gives the bound parameter the `vector` type
        # OID. A bare list is sent as float8[], which Postgres can only coerce
        # when a target column supplies the type, it fails for `<=>` in search.
        vector = Vector(await self._embedder.embed(f"{doc.title}\n\n{doc.body}"))
        async with self._pool.connection() as conn:
            await register_vector_async(conn)
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    INSERT INTO kb_documents
                        (external_id, title, body, category, embedding)
                    VALUES (%s, %s, %s, %s, %s)
                    ON CONFLICT (external_id) DO UPDATE SET
                        title = EXCLUDED.title,
                        body = EXCLUDED.body,
                        category = EXCLUDED.category,
                        embedding = EXCLUDED.embedding,
                        updated_at = NOW()
                    RETURNING id, external_id, title, category, updated_at
                    """,
                    (
                        doc.external_id,
                        doc.title,
                        doc.body,
                        doc.category,
                        vector,
                    ),
                )
                row = await cur.fetchone()
        assert row is not None  # RETURNING guarantees a row
        return KbDocumentOut(
            id=str(row[0]),
            external_id=row[1],
            title=row[2],
            category=row[3],
            updated_at=row[4],
        )

    async def search(self, query: str, k: int) -> list[KbSearchHit]:
        if k <= 0:
            return []
        vector = Vector(await self._embedder.embed(query))
        async with self._pool.connection() as conn:
            await register_vector_async(conn)
            async with conn.cursor() as cur:
                # 1 - cosine_distance == cosine similarity; higher is better,
                # so the ORDER BY on distance gives us "closest first".
                await cur.execute(
                    """
                    SELECT id,
                           external_id,
                           title,
                           body,
                           category,
                           1 - (embedding <=> %s) AS score
                    FROM kb_documents
                    ORDER BY embedding <=> %s
                    LIMIT %s
                    """,
                    (vector, vector, k),
                )
                rows = await cur.fetchall()
        return [
            KbSearchHit(
                id=str(row[0]),
                external_id=row[1],
                title=row[2],
                body=row[3],
                category=row[4],
                score=float(row[5]),
            )
            for row in rows
        ]
