from httpx import AsyncClient

from tests.conftest import auth


async def test_kb_requires_internal_key(client: AsyncClient) -> None:
    response = await client.post(
        "/kb/documents",
        json={"external_id": "kb-1", "title": "T", "body": "B"},
    )
    assert response.status_code == 401
    assert response.json() == {"detail": "unauthorized"}


async def test_kb_rejects_wrong_key(client: AsyncClient) -> None:
    response = await client.post(
        "/kb/documents",
        headers={"X-Internal-Key": "definitely-not-the-key"},
        json={"external_id": "kb-1", "title": "T", "body": "B"},
    )
    assert response.status_code == 401


async def test_kb_accepts_correct_key(client: AsyncClient) -> None:
    response = await client.post(
        "/kb/documents",
        headers=auth(),
        json={"external_id": "kb-1", "title": "T", "body": "B"},
    )
    assert response.status_code == 201


async def test_triage_requires_internal_key(client: AsyncClient) -> None:
    response = await client.post(
        "/triage",
        json={"ticket_id": "t-1", "title": "help", "body": "please"},
    )
    assert response.status_code == 401


async def test_search_requires_internal_key(client: AsyncClient) -> None:
    response = await client.get("/kb/search", params={"q": "hello"})
    assert response.status_code == 401
