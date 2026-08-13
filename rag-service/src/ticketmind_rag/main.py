"""FastAPI application entrypoint.

Public surface:

- ``GET /health``, unauthenticated liveness probe.
- ``POST /kb/documents``. Upsert a knowledge-base document (auth required).
- ``GET /kb/search``. Retrieve nearest-neighbour KB documents (auth required).
- ``POST /triage``. End-to-end triage of a new ticket (auth required).

All authenticated endpoints share the ``X-Internal-Key`` guard defined in
:mod:`deps`; the backend is the only intended caller.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Query
from fastapi.responses import JSONResponse

from . import __version__
from .config import Settings, get_settings
from .db import build_pool, ensure_schema
from .deps import (
    get_kb,
    get_triage,
    require_internal_key,
)
from .embeddings import Embedder, VoyageEmbedder
from .errors import UpstreamError
from .kb import KbRepository
from .llm import AnthropicChat, ChatModel
from .schemas import (
    HealthResponse,
    KbDocumentIn,
    KbDocumentOut,
    KbSearchResponse,
    TriageRequest,
    TriageResponse,
)
from .triage import TriageService

logger = logging.getLogger(__name__)


def _build_embedder(settings: Settings) -> Embedder:
    return VoyageEmbedder(
        api_key=settings.voyage_api_key.get_secret_value(),
        model=settings.embedding_model,
        dimension=settings.embedding_dim,
    )


def _build_chat(settings: Settings) -> ChatModel:
    return AnthropicChat(
        api_key=settings.anthropic_api_key.get_secret_value(),
        model=settings.chat_model,
    )


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Wire live components at startup if they weren't pre-seeded by tests.

    The test harness sets ``app.state.settings``, ``.pool``, ``.embedder``,
    and ``.chat`` before entering the lifespan so we don't spin up a Voyage
    or Anthropic client for unit tests.
    """

    state = app.state

    settings: Settings = getattr(state, "settings", None) or get_settings()
    state.settings = settings

    if not hasattr(state, "pool"):
        state.pool = await build_pool(settings.database_url)
        await ensure_schema(state.pool, settings.embedding_dim)

    if not hasattr(state, "embedder"):
        state.embedder = _build_embedder(settings)

    if not hasattr(state, "chat"):
        state.chat = _build_chat(settings)

    state.kb = KbRepository(pool=state.pool, embedder=state.embedder)
    state.triage = TriageService(
        repo=state.kb,
        chat=state.chat,
        retrieval_k=settings.retrieval_k,
    )

    try:
        yield
    finally:
        # Only close the pool if we opened it, tests that supplied their
        # own pool via app.state manage its lifetime themselves.
        if getattr(state, "_owns_pool", True):
            await state.pool.close()


def create_app() -> FastAPI:
    app = FastAPI(
        title="TicketMind RAG",
        version=__version__,
        lifespan=lifespan,
    )

    @app.exception_handler(UpstreamError)
    async def _upstream_error(_request, exc: UpstreamError) -> JSONResponse:  # type: ignore[no-untyped-def]
        # 502 Bad Gateway: a provider we depend on failed, or produced
        # something we could not parse. Not the caller's fault; retrying may
        # help. TriageError subclasses UpstreamError, so this one handler
        # covers both an unusable Claude reply and a call that never landed.
        logger.warning("upstream error: %s", exc)
        return JSONResponse(status_code=502, content={"detail": "upstream_error"})

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok", version=__version__)

    @app.post(
        "/kb/documents",
        response_model=KbDocumentOut,
        dependencies=[Depends(require_internal_key)],
        status_code=201,
    )
    async def upsert_document(
        payload: KbDocumentIn,
        repo: KbRepository = Depends(get_kb),
    ) -> KbDocumentOut:
        return await repo.upsert(payload)

    @app.get(
        "/kb/search",
        response_model=KbSearchResponse,
        dependencies=[Depends(require_internal_key)],
    )
    async def search_documents(
        q: str = Query(min_length=1, max_length=2048),
        k: int = Query(default=5, ge=1, le=50),
        repo: KbRepository = Depends(get_kb),
    ) -> KbSearchResponse:
        hits = await repo.search(query=q, k=k)
        return KbSearchResponse(hits=hits)

    @app.post(
        "/triage",
        response_model=TriageResponse,
        dependencies=[Depends(require_internal_key)],
    )
    async def triage_ticket(
        payload: TriageRequest,
        service: TriageService = Depends(get_triage),
    ) -> TriageResponse:
        return await service.triage(payload)

    return app


app = create_app()
