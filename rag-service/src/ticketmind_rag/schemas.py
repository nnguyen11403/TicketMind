"""Pydantic request / response schemas exposed at the HTTP surface.

Kept deliberately small: the RAG service only speaks to the Spring backend, so
schemas mirror what the backend needs and nothing more.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Priority = Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]


class KbDocumentIn(BaseModel):
    """Payload for POST /kb/documents (upsert-by-external-id)."""

    model_config = ConfigDict(str_strip_whitespace=True)

    external_id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=256)
    body: str = Field(min_length=1, max_length=32_000)
    category: str | None = Field(default=None, max_length=64)


class KbDocumentOut(BaseModel):
    id: str
    external_id: str
    title: str
    category: str | None
    updated_at: datetime


class KbSearchHit(BaseModel):
    id: str
    external_id: str
    title: str
    body: str
    category: str | None
    score: float


class KbSearchResponse(BaseModel):
    hits: list[KbSearchHit]


class TriageRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    ticket_id: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=256)
    body: str = Field(min_length=1, max_length=32_000)


class TriageCitation(BaseModel):
    external_id: str
    title: str
    score: float


class TriageResponse(BaseModel):
    category: str = Field(min_length=1, max_length=64)
    priority: Priority
    summary: str = Field(min_length=1, max_length=2_000)
    suggested_resolution: str = Field(min_length=1, max_length=8_000)
    citations: list[TriageCitation]


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    version: str
