"""Shared pytest fixtures.

Two big decisions made here:

- **One pgvector Testcontainer per session.** Container startup dominates test
  time, so we boot it once, wipe rows between tests, and let the schema
  survive the whole run.
- **App instances receive fakes for embedder + chat.** We're testing our
  glue code and SQL, not Anthropic's or Voyage's — plugging real clients into
  a unit-test suite would be slow, flaky, and costs actual money.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from asgi_lifespan import LifespanManager
from httpx import ASGITransport, AsyncClient
from pgvector.psycopg import register_vector_async
from psycopg_pool import AsyncConnectionPool
from testcontainers.postgres import PostgresContainer

from ticketmind_rag.config import Settings
from ticketmind_rag.db import ensure_schema
from ticketmind_rag.embeddings import FakeEmbedder
from ticketmind_rag.llm import FakeChat
from ticketmind_rag.main import create_app

INTERNAL_KEY = "test-internal-key"
EMBED_DIM = 8  # small dimensions keep test vectors readable in failure output


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope="session")
def pg_container() -> PostgresContainer:
    """Start a pgvector-enabled Postgres for the whole test session."""

    container = PostgresContainer(
        image="pgvector/pgvector:pg16",
        username="rag",
        password="rag",
        dbname="rag",
        driver=None,
    )
    container.start()
    try:
        yield container
    finally:
        container.stop()


def _dsn(container: PostgresContainer) -> str:
    return (
        f"postgresql://{container.username}:{container.password}"
        f"@{container.get_container_host_ip()}:"
        f"{container.get_exposed_port(5432)}/{container.dbname}"
    )


@pytest_asyncio.fixture
async def pool(pg_container: PostgresContainer) -> AsyncIterator[AsyncConnectionPool]:
    pool = AsyncConnectionPool(_dsn(pg_container), min_size=1, max_size=4, open=False)
    await pool.open()
    await ensure_schema(pool, EMBED_DIM)
    async with pool.connection() as conn:
        await register_vector_async(conn)
        async with conn.cursor() as cur:
            await cur.execute("TRUNCATE TABLE kb_documents")
    try:
        yield pool
    finally:
        await pool.close()


@pytest.fixture
def settings(pg_container: PostgresContainer) -> Settings:
    return Settings(
        DATABASE_URL=_dsn(pg_container),
        RAG_INTERNAL_API_KEY=INTERNAL_KEY,
        ANTHROPIC_API_KEY="test-anthropic-key",
        VOYAGE_API_KEY="test-voyage-key",
        RAG_EMBEDDING_DIM=EMBED_DIM,
        RAG_RETRIEVAL_K=3,
    )  # type: ignore[call-arg]


@pytest.fixture
def fake_embedder() -> FakeEmbedder:
    return FakeEmbedder(dimension=EMBED_DIM)


@pytest.fixture
def fake_chat_json() -> str:
    return (
        '{"category": "billing",'
        ' "priority": "HIGH",'
        ' "summary": "Card was double-charged.",'
        ' "suggested_resolution": "Refund the duplicate charge and email the customer.",'
        ' "citations": [{"external_id": "kb-1", "title": "Double-charge policy"}]}'
    )


@pytest_asyncio.fixture
async def client(
    settings: Settings,
    pool: AsyncConnectionPool,
    fake_embedder: FakeEmbedder,
    fake_chat_json: str,
) -> AsyncIterator[AsyncClient]:
    app = create_app()
    app.state.settings = settings
    app.state.pool = pool
    app.state.embedder = fake_embedder
    app.state.chat = FakeChat(canned=fake_chat_json)
    app.state._owns_pool = False
    async with LifespanManager(app):
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac


def auth() -> dict[str, str]:
    return {"X-Internal-Key": INTERNAL_KEY}
