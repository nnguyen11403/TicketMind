import pytest
from httpx import AsyncClient

from tests.conftest import auth
from ticketmind_rag.errors import UpstreamError
from ticketmind_rag.kb import KbRepository
from ticketmind_rag.llm import FakeChat
from ticketmind_rag.schemas import KbDocumentIn, KbSearchHit, TriageRequest
from ticketmind_rag.triage import TriageError, TriageService, _extract_json


@pytest.mark.asyncio
async def test_triage_service_returns_parsed_response(pool, fake_embedder) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder)
    await repo.upsert(KbDocumentIn(external_id="kb-1", title="Double charge", body="Refund policy"))
    chat = FakeChat(
        canned=(
            '{"category":"billing","priority":"HIGH",'
            '"summary":"Card was double-charged.",'
            '"suggested_resolution":"Refund duplicate charge.",'
            '"citations":[{"external_id":"kb-1","title":"Double charge"}]}'
        )
    )
    service = TriageService(repo=repo, chat=chat, retrieval_k=3)
    resp = await service.triage(
        TriageRequest(ticket_id="t-1", title="charged twice", body="please help")
    )
    assert resp.category == "billing"
    assert resp.priority == "HIGH"
    assert len(resp.citations) == 1
    assert resp.citations[0].external_id == "kb-1"
    # Score is drawn from the retrieved neighbour, not the LLM.
    assert 0.0 <= resp.citations[0].score <= 2.0


@pytest.mark.asyncio
async def test_triage_service_lowercase_priority_is_normalised(pool, fake_embedder) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder)
    chat = FakeChat(
        canned=(
            '{"category":"general","priority":"low",'
            '"summary":"s","suggested_resolution":"r","citations":[]}'
        )
    )
    service = TriageService(repo=repo, chat=chat, retrieval_k=1)
    resp = await service.triage(TriageRequest(ticket_id="t-1", title="a", body="b"))
    assert resp.priority == "LOW"


@pytest.mark.asyncio
async def test_triage_service_rejects_invalid_priority(pool, fake_embedder) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder)
    chat = FakeChat(
        canned=(
            '{"category":"general","priority":"WHENEVER",'
            '"summary":"s","suggested_resolution":"r","citations":[]}'
        )
    )
    service = TriageService(repo=repo, chat=chat, retrieval_k=1)
    with pytest.raises(TriageError):
        await service.triage(TriageRequest(ticket_id="t-1", title="a", body="b"))


@pytest.mark.asyncio
async def test_triage_service_ignores_malformed_citations(pool, fake_embedder) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder)
    chat = FakeChat(
        canned=(
            '{"category":"c","priority":"MEDIUM","summary":"s",'
            '"suggested_resolution":"r",'
            '"citations":[{"title":"no id"},"not a dict",{"external_id":123},'
            '{"external_id":"kb-x","title":"unknown"}]}'
        )
    )
    service = TriageService(repo=repo, chat=chat, retrieval_k=1)
    resp = await service.triage(TriageRequest(ticket_id="t-1", title="a", body="b"))
    # Only the valid dict with a string external_id survives; its score is 0
    # because it wasn't in the neighbour set.
    assert len(resp.citations) == 1
    assert resp.citations[0].external_id == "kb-x"
    assert resp.citations[0].score == 0.0


def test_extract_json_strips_fences() -> None:
    payload = _extract_json('```json\n{"a": 1}\n```')
    assert payload == {"a": 1}


def test_extract_json_raises_on_missing_object() -> None:
    with pytest.raises(TriageError):
        _extract_json("no json here")


def test_extract_json_raises_on_invalid_json() -> None:
    with pytest.raises(TriageError):
        _extract_json("{ not : valid }")


async def test_triage_endpoint_502_on_upstream_parse_failure(client: AsyncClient) -> None:
    # Swap the injected FakeChat for one that emits a missing-field payload.
    transport = client._transport  # type: ignore[attr-defined]
    app = transport.app
    app.state.chat = FakeChat(canned='{"category":"c","priority":"HIGH","summary":"s"}')
    app.state.triage = TriageService(repo=app.state.kb, chat=app.state.chat, retrieval_k=1)
    response = await client.post(
        "/triage",
        headers=auth(),
        json={"ticket_id": "t-1", "title": "hello", "body": "world"},
    )
    assert response.status_code == 502
    assert response.json() == {"detail": "upstream_error"}


async def test_triage_endpoint_returns_response(client: AsyncClient) -> None:
    # First stash a KB doc so triage retrieval has something to cite.
    await client.post(
        "/kb/documents",
        headers=auth(),
        json={"external_id": "kb-1", "title": "Double-charge policy", "body": "b"},
    )
    response = await client.post(
        "/triage",
        headers=auth(),
        json={"ticket_id": "t-1", "title": "charged twice", "body": "please help"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["category"] == "billing"
    assert body["priority"] == "HIGH"
    assert body["citations"][0]["external_id"] == "kb-1"


def test_kb_search_hit_used_in_score_lookup() -> None:
    # Small anchor to make sure KbSearchHit is importable at test time, the
    # triage service depends on the shape when it composes scores.
    hit = KbSearchHit(id="1", external_id="kb-1", title="t", body="b", category=None, score=0.5)
    assert hit.score == 0.5


async def test_provider_failure_is_502_not_500(client: AsyncClient) -> None:
    """A dead embedding provider must look like an upstream fault, not a crash.

    Before this, a missing VOYAGE_API_KEY produced a 500 with a stack trace in
    the logs. Functionally survivable (the backend keeps the ticket) but a
    terrible first-run experience for anyone who just ran `docker compose up`.
    """

    class DeadEmbedder:
        dimension = 8

        async def embed(self, text: str) -> list[float]:
            raise UpstreamError("embedding provider failed: no API key")

        async def embed_batch(self, texts: list[str]) -> list[list[float]]:
            raise UpstreamError("embedding provider failed: no API key")

    transport = client._transport  # type: ignore[attr-defined]
    app = transport.app
    app.state.kb = KbRepository(pool=app.state.pool, embedder=DeadEmbedder())
    app.state.triage = TriageService(repo=app.state.kb, chat=app.state.chat, retrieval_k=1)

    response = await client.post(
        "/triage",
        headers=auth(),
        json={"ticket_id": "t-1", "title": "t", "body": "b"},
    )
    assert response.status_code == 502
    assert response.json() == {"detail": "upstream_error"}


async def test_kb_upsert_also_reports_provider_failure_as_502(client: AsyncClient) -> None:
    class DeadEmbedder:
        dimension = 8

        async def embed(self, text: str) -> list[float]:
            raise UpstreamError("embedding provider failed: no API key")

        async def embed_batch(self, texts: list[str]) -> list[list[float]]:
            raise UpstreamError("embedding provider failed: no API key")

    transport = client._transport  # type: ignore[attr-defined]
    app = transport.app
    app.state.kb = KbRepository(pool=app.state.pool, embedder=DeadEmbedder())

    response = await client.post(
        "/kb/documents",
        headers=auth(),
        json={"external_id": "kb-1", "title": "t", "body": "b"},
    )
    assert response.status_code == 502
