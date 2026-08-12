import math

import pytest

from ticketmind_rag.embeddings import Embedder, FakeEmbedder


@pytest.mark.asyncio
async def test_fake_embedder_is_deterministic() -> None:
    embedder = FakeEmbedder(dimension=32)
    v1 = await embedder.embed("hello")
    v2 = await embedder.embed("hello")
    assert v1 == v2


@pytest.mark.asyncio
async def test_fake_embedder_differs_across_inputs() -> None:
    embedder = FakeEmbedder(dimension=32)
    v1 = await embedder.embed("apples")
    v2 = await embedder.embed("bananas")
    assert v1 != v2


@pytest.mark.asyncio
async def test_fake_embedder_produces_unit_vector() -> None:
    embedder = FakeEmbedder(dimension=64)
    vec = await embedder.embed("anything")
    norm = math.sqrt(sum(x * x for x in vec))
    assert math.isclose(norm, 1.0, rel_tol=1e-6)


@pytest.mark.asyncio
async def test_fake_embedder_batch_matches_single() -> None:
    embedder = FakeEmbedder(dimension=16)
    single = await embedder.embed("x")
    batch = await embedder.embed_batch(["x", "y", "z"])
    assert batch[0] == single
    assert len(batch) == 3
    assert len(batch[0]) == 16


@pytest.mark.asyncio
async def test_fake_embedder_empty_batch() -> None:
    embedder = FakeEmbedder(dimension=16)
    assert await embedder.embed_batch([]) == []


def test_fake_embedder_satisfies_protocol() -> None:
    assert isinstance(FakeEmbedder(dimension=4), Embedder)
