from httpx import AsyncClient

from ticketmind_rag import __version__


async def test_health_returns_ok_and_version(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body == {"status": "ok", "version": __version__}


async def test_health_is_unauthenticated(client: AsyncClient) -> None:
    # No X-Internal-Key header, the health probe must still succeed.
    response = await client.get("/health")
    assert response.status_code == 200
