"""Prompt templates used by the triage service.

Kept in one place so wording changes are a single-file diff and prompt
snapshots can be compared across versions if we start drifting.
"""

from __future__ import annotations

from .schemas import KbSearchHit

TRIAGE_SYSTEM_PROMPT = """You are TicketMind's support triage assistant.

Given a customer ticket and up to five similar historical tickets, respond
with a single JSON object and nothing else. The JSON object must have exactly
these keys:

- "category": short category string (max 64 chars, e.g. "billing", "auth",
  "shipping").
- "priority": one of "LOW", "MEDIUM", "HIGH", "URGENT".
- "summary": one-paragraph summary of what the customer is asking (<= 500
  chars).
- "suggested_resolution": actionable next-step guidance for the human agent
  (<= 2000 chars). Reference historical resolutions where useful.
- "citations": array of {"external_id": ..., "title": ...} objects for the
  historical tickets you actually leaned on. Empty array if none apply.

Never include markdown code fences, prose before or after the JSON, or extra
keys. If you cannot classify the ticket confidently, use category "general"
and priority "MEDIUM".
"""


def build_triage_user_prompt(
    title: str,
    body: str,
    neighbours: list[KbSearchHit],
) -> str:
    """Compose the user-turn prompt Claude sees for a triage request."""

    parts = [
        "NEW TICKET",
        f"Title: {title}",
        "Body:",
        body.strip(),
        "",
    ]

    if neighbours:
        parts.append("SIMILAR HISTORICAL TICKETS")
        for i, hit in enumerate(neighbours, start=1):
            parts.extend(
                [
                    f"[{i}] external_id={hit.external_id} "
                    f"score={hit.score:.3f} "
                    f"category={hit.category or 'unknown'}",
                    f"Title: {hit.title}",
                    "Body:",
                    hit.body.strip(),
                    "",
                ]
            )
    else:
        parts.append("SIMILAR HISTORICAL TICKETS: (none)")
        parts.append("")

    parts.append("Respond with the JSON object described in the system prompt.")
    return "\n".join(parts)
