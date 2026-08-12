import pytest
from pydantic import ValidationError

from ticketmind_rag.schemas import (
    KbDocumentIn,
    TriageRequest,
    TriageResponse,
)


def test_kb_document_in_strips_whitespace() -> None:
    doc = KbDocumentIn(
        external_id="  kb-1  ",
        title="  title  ",
        body="  body  ",
        category="  billing  ",
    )
    assert doc.external_id == "kb-1"
    assert doc.title == "title"
    assert doc.body == "body"
    assert doc.category == "billing"


def test_kb_document_in_rejects_empty_body() -> None:
    with pytest.raises(ValidationError):
        KbDocumentIn(external_id="kb-1", title="t", body="")


def test_triage_request_rejects_missing_title() -> None:
    with pytest.raises(ValidationError):
        TriageRequest(ticket_id="t-1", title="", body="hello")


def test_triage_response_rejects_invalid_priority() -> None:
    with pytest.raises(ValidationError):
        TriageResponse(
            category="c",
            priority="EXTREME",  # type: ignore[arg-type]
            summary="s",
            suggested_resolution="r",
            citations=[],
        )


@pytest.mark.parametrize("priority", ["LOW", "MEDIUM", "HIGH", "CRITICAL"])
def test_triage_response_priorities_match_the_backend_enum(priority: str) -> None:
    # These four are the only values the backend's TicketPriority enum and the
    # `tickets_priority_check` constraint in V1 accept — the DB rejects anything
    # else, so this vocabulary is not ours to extend unilaterally.
    response = TriageResponse(
        category="c",
        priority=priority,  # type: ignore[arg-type]
        summary="s",
        suggested_resolution="r",
        citations=[],
    )
    assert response.priority == priority
