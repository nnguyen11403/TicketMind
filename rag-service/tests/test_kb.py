import pytest
from httpx import AsyncClient
from psycopg_pool import AsyncConnectionPool

from tests.conftest import EMBED_DIM, auth
from ticketmind_rag.crypto import FieldCipher
from ticketmind_rag.embeddings import FakeEmbedder
from ticketmind_rag.kb import KbRepository
from ticketmind_rag.schemas import KbDocumentIn


@pytest.mark.asyncio
async def test_upsert_document_persists_row(
    pool: AsyncConnectionPool, fake_embedder: FakeEmbedder, cipher: FieldCipher
) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder, cipher=cipher)
    out = await repo.upsert(
        KbDocumentIn(external_id="kb-1", title="Refunds", body="How to refund", category="billing")
    )
    assert out.external_id == "kb-1"
    assert out.title == "Refunds"
    assert out.category == "billing"


@pytest.mark.asyncio
async def test_upsert_document_updates_on_conflict(
    pool: AsyncConnectionPool, fake_embedder: FakeEmbedder, cipher: FieldCipher
) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder, cipher=cipher)
    first = await repo.upsert(KbDocumentIn(external_id="kb-1", title="Old", body="Old body"))
    second = await repo.upsert(KbDocumentIn(external_id="kb-1", title="New", body="New body"))
    # Same external_id → same PK, updated fields.
    assert first.id == second.id
    assert second.title == "New"


@pytest.mark.asyncio
async def test_search_returns_closest_first(
    pool: AsyncConnectionPool, fake_embedder: FakeEmbedder, cipher: FieldCipher
) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder, cipher=cipher)
    await repo.upsert(KbDocumentIn(external_id="kb-a", title="alpha", body="Alpha body"))
    await repo.upsert(KbDocumentIn(external_id="kb-b", title="bravo", body="Bravo body"))
    hits = await repo.search("alpha", k=2)
    assert len(hits) == 2
    # Ordering guarantee: querying "alpha" should surface the "alpha" doc first
    # because the FakeEmbedder is deterministic and identity-of-input yields
    # identity-of-vector; nothing else in the store hashes to the same bytes.
    assert hits[0].external_id == "kb-a"


@pytest.mark.asyncio
async def test_search_k_zero_short_circuits(
    pool: AsyncConnectionPool, fake_embedder: FakeEmbedder, cipher: FieldCipher
) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder, cipher=cipher)
    await repo.upsert(KbDocumentIn(external_id="kb-1", title="t", body="b"))
    assert await repo.search("anything", k=0) == []


async def test_kb_endpoints_end_to_end(client: AsyncClient) -> None:
    # POST /kb/documents
    create = await client.post(
        "/kb/documents",
        headers=auth(),
        json={"external_id": "kb-1", "title": "hello", "body": "world"},
    )
    assert create.status_code == 201
    created = create.json()
    assert created["external_id"] == "kb-1"

    # GET /kb/search
    search = await client.get(
        "/kb/search",
        headers=auth(),
        params={"q": "hello", "k": 1},
    )
    assert search.status_code == 200
    hits = search.json()["hits"]
    assert len(hits) == 1
    assert hits[0]["external_id"] == "kb-1"
    assert -1.0 <= hits[0]["score"] <= 1.0  # cosine similarity is in [-1, 1]


async def test_kb_search_rejects_empty_query(client: AsyncClient) -> None:
    response = await client.get("/kb/search", headers=auth(), params={"q": ""})
    assert response.status_code == 422


async def test_kb_search_rejects_oversized_k(client: AsyncClient) -> None:
    response = await client.get("/kb/search", headers=auth(), params={"q": "hi", "k": 999})
    assert response.status_code == 422


def _use_embed_dim() -> int:
    # Anchor for the conftest constant so a rename fails loudly.
    return EMBED_DIM
