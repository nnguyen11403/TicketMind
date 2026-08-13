"""FastAPI dependency wiring.

The service is small enough that a proper DI container would be overkill; the
app stashes long-lived objects (pool, embedder, chat, repo, triage service)
on ``app.state`` at startup and dependency callables pull them off the
:class:`Request`. This makes it trivial for tests to swap components before
the app is entered, they just poke ``app.state`` directly.
"""

from __future__ import annotations

import hmac

from fastapi import Depends, Header, HTTPException, Request, status
from psycopg_pool import AsyncConnectionPool

from .config import Settings
from .embeddings import Embedder
from .kb import KbRepository
from .llm import ChatModel
from .triage import TriageService


def get_settings_dep(request: Request) -> Settings:
    return request.app.state.settings  # type: ignore[no-any-return]


def get_pool(request: Request) -> AsyncConnectionPool:
    return request.app.state.pool  # type: ignore[no-any-return]


def get_embedder(request: Request) -> Embedder:
    return request.app.state.embedder  # type: ignore[no-any-return]


def get_chat(request: Request) -> ChatModel:
    return request.app.state.chat  # type: ignore[no-any-return]


def get_kb(request: Request) -> KbRepository:
    return request.app.state.kb  # type: ignore[no-any-return]


def get_triage(request: Request) -> TriageService:
    return request.app.state.triage  # type: ignore[no-any-return]


def require_internal_key(
    x_internal_key: str | None = Header(default=None, alias="X-Internal-Key"),
    settings: Settings = Depends(get_settings_dep),
) -> None:
    """Constant-time compare the ``X-Internal-Key`` header against the
    configured secret. Missing or wrong key → 401.

    We do **not** distinguish "missing" from "wrong" in the response body:
    both surface as ``unauthorized`` so external probing can't tell the two
    apart. That matters when the RAG service is exposed inside the compose
    network. Anyone who lands a foothold on another container shouldn't be
    able to enumerate whether they need to guess a header at all.
    """

    expected = settings.internal_api_key.get_secret_value()
    if x_internal_key is None or not hmac.compare_digest(x_internal_key, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="unauthorized",
        )
