"""Ticket triage pipeline: retrieve → prompt → parse."""

from __future__ import annotations

import json
import re
from typing import get_args

from .kb import KbRepository
from .llm import ChatModel
from .prompts import TRIAGE_SYSTEM_PROMPT, build_triage_user_prompt
from .schemas import Priority, TriageCitation, TriageRequest, TriageResponse

_VALID_PRIORITIES: frozenset[str] = frozenset(get_args(Priority))
_JSON_BLOCK_RE = re.compile(r"\{.*\}", re.DOTALL)


class TriageError(ValueError):
    """Raised when the LLM produced a response we can't turn into a
    :class:`TriageResponse`. Callers surface this as HTTP 502 so the backend
    treats it as an upstream problem, not a bad request."""


class TriageService:
    def __init__(self, repo: KbRepository, chat: ChatModel, retrieval_k: int) -> None:
        self._repo = repo
        self._chat = chat
        self._retrieval_k = retrieval_k

    async def triage(self, request: TriageRequest) -> TriageResponse:
        neighbours = await self._repo.search(
            query=f"{request.title}\n\n{request.body}",
            k=self._retrieval_k,
        )
        user_prompt = build_triage_user_prompt(
            title=request.title,
            body=request.body,
            neighbours=neighbours,
        )
        raw = await self._chat.complete(TRIAGE_SYSTEM_PROMPT, user_prompt)
        payload = _extract_json(raw)
        return _to_response(payload, neighbours)


def _extract_json(raw: str) -> dict[str, object]:
    """Pull the first JSON object out of the LLM response.

    We ask the model to reply with pure JSON, but real Anthropic responses
    occasionally wrap in prose or code fences. Extracting the first `{...}`
    block keeps the pipeline resilient without lying about how strict the
    system prompt is.
    """

    match = _JSON_BLOCK_RE.search(raw)
    if not match:
        raise TriageError("LLM response did not contain a JSON object")
    try:
        parsed = json.loads(match.group(0))
    except json.JSONDecodeError as exc:
        raise TriageError(f"LLM response was not valid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise TriageError("LLM response JSON was not an object")
    return parsed


def _to_response(
    payload: dict[str, object],
    neighbours: list,  # list[KbSearchHit], loose type to keep the import lean
) -> TriageResponse:
    category = _require_str(payload, "category")
    priority_raw = _require_str(payload, "priority").upper()
    if priority_raw not in _VALID_PRIORITIES:
        raise TriageError(f"invalid priority: {priority_raw!r}")
    summary = _require_str(payload, "summary")
    suggested = _require_str(payload, "suggested_resolution")
    citations_raw = payload.get("citations", [])
    if not isinstance(citations_raw, list):
        raise TriageError("citations must be a list")

    # Attach retrieval scores from the neighbour we actually searched, so the
    # backend can display "this citation was 0.82 similar" without trusting
    # the LLM's own confidence estimates.
    score_by_external = {n.external_id: n.score for n in neighbours}
    citations: list[TriageCitation] = []
    for entry in citations_raw:
        if not isinstance(entry, dict):
            continue
        external_id = entry.get("external_id")
        title = entry.get("title") or ""
        if not isinstance(external_id, str) or not external_id:
            continue
        citations.append(
            TriageCitation(
                external_id=external_id,
                title=str(title),
                score=score_by_external.get(external_id, 0.0),
            )
        )

    return TriageResponse(
        category=category,
        priority=priority_raw,  # type: ignore[arg-type]  # validated above
        summary=summary,
        suggested_resolution=suggested,
        citations=citations,
    )


def _require_str(payload: dict[str, object], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise TriageError(f"missing or empty string field: {key!r}")
    return value.strip()
