"""Embedding provider abstraction.

The service defines a small :class:`Embedder` Protocol so tests can swap in a
deterministic fake without carrying the ``voyageai`` HTTP client.
"""

from __future__ import annotations

import hashlib
import math
from typing import Protocol, runtime_checkable

import voyageai

from .errors import UpstreamError


@runtime_checkable
class Embedder(Protocol):
    """Anything that maps text to a fixed-dimension unit-ish vector."""

    dimension: int

    async def embed(self, text: str) -> list[float]: ...

    async def embed_batch(self, texts: list[str]) -> list[list[float]]: ...


class VoyageEmbedder:
    """Production embedder using Voyage AI (recommended for Anthropic stacks)."""

    def __init__(self, api_key: str, model: str, dimension: int) -> None:
        self._client = voyageai.AsyncClient(api_key=api_key)
        self._model = model
        self.dimension = dimension

    async def embed(self, text: str) -> list[float]:
        vectors = await self.embed_batch([text])
        return vectors[0]

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            result = await self._client.embed(texts=texts, model=self._model)
        except Exception as exc:
            # Deliberately broad: every failure mode of an outbound provider
            # call — auth, quota, timeout, transport — is an upstream problem
            # from our side. Without this a missing VOYAGE_API_KEY surfaced as
            # a 500 with a stack trace instead of a clean 502.
            raise UpstreamError(f"embedding provider failed: {exc}") from exc
        # voyageai returns a list of Python lists on `.embeddings`. Cast to
        # plain floats to keep pgvector's binary encoder happy.
        return [[float(x) for x in vec] for vec in result.embeddings]


class FakeEmbedder:
    """Deterministic hash-based embedder for tests.

    Produces a unit-length vector so cosine distance ordering is meaningful
    without pulling in numpy. The mapping is stable per input string, so two
    calls with the same text return identical vectors — the property real
    embedders promise.
    """

    def __init__(self, dimension: int = 1024) -> None:
        self.dimension = dimension

    async def embed(self, text: str) -> list[float]:
        return self._vector_for(text)

    async def embed_batch(self, texts: list[str]) -> list[list[float]]:
        return [self._vector_for(t) for t in texts]

    def _vector_for(self, text: str) -> list[float]:
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        # Stretch the 32-byte digest across ``dimension`` slots by repeating.
        raw = [(digest[i % len(digest)] - 128) / 128.0 for i in range(self.dimension)]
        norm = math.sqrt(sum(v * v for v in raw)) or 1.0
        return [v / norm for v in raw]
