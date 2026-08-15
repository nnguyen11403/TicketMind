import base64

import pytest
from httpx import AsyncClient
from psycopg_pool import AsyncConnectionPool

from tests.conftest import auth
from ticketmind_rag.crypto import FieldCipher
from ticketmind_rag.embeddings import FakeEmbedder
from ticketmind_rag.kb import KbRepository
from ticketmind_rag.schemas import KbDocumentIn


def test_round_trips(cipher: FieldCipher) -> None:
    assert cipher.decrypt(cipher.encrypt("hello")) == "hello"


def test_none_passes_through(cipher: FieldCipher) -> None:
    assert cipher.encrypt(None) is None
    assert cipher.decrypt(None) is None


def test_same_input_encrypts_differently(cipher: FieldCipher) -> None:
    """A random IV per value, so identical documents aren't identical rows."""

    assert cipher.encrypt("same") != cipher.encrypt("same")


def test_legacy_plaintext_reads_through(cipher: FieldCipher) -> None:
    assert cipher.decrypt("written before encryption") == "written before encryption"


def test_tampered_ciphertext_is_rejected(cipher: FieldCipher) -> None:
    encrypted = cipher.encrypt("original")
    assert encrypted is not None
    with pytest.raises(ValueError, match="decryption failed"):
        cipher.decrypt(encrypted[:-4] + "AAAA")


def test_wrong_key_is_rejected(cipher: FieldCipher) -> None:
    other = FieldCipher(b"a-completely-different-key-32byt")
    encrypted = cipher.encrypt("secret")
    assert encrypted is not None
    with pytest.raises(ValueError, match="decryption failed"):
        other.decrypt(encrypted)


def test_short_key_is_rejected() -> None:
    with pytest.raises(ValueError, match="32 bytes"):
        FieldCipher(b"too-short")


def test_missing_key_is_rejected() -> None:
    with pytest.raises(ValueError, match="APP_ENCRYPTION_KEY"):
        FieldCipher.from_base64("")


def test_backend_written_envelope_is_readable(cipher: FieldCipher) -> None:
    """Cross-service format check.

    The Java FieldCipher writes v1:<b64 iv>:<b64 ciphertext||tag>, and the
    backend mirrors resolved tickets into this service's table. If either side
    changed its envelope independently, the mirror would start returning
    garbage into a Claude prompt rather than failing, so pin the shape.
    """

    encrypted = cipher.encrypt("resolved ticket body")
    assert encrypted is not None
    version, iv_b64, ciphertext_b64 = encrypted.split(":", 2)
    assert version == "v1"
    assert len(base64.b64decode(iv_b64)) == 12
    # ciphertext plus the 128-bit GCM tag
    assert len(base64.b64decode(ciphertext_b64)) == len("resolved ticket body") + 16


@pytest.mark.asyncio
async def test_document_text_is_ciphertext_at_rest(
    pool: AsyncConnectionPool, fake_embedder: FakeEmbedder, cipher: FieldCipher
) -> None:
    repo = KbRepository(pool=pool, embedder=fake_embedder, cipher=cipher)
    await repo.upsert(
        KbDocumentIn(
            external_id="kb-secret",
            title="Refund for card 4242",
            body="Customer jane@example.com was double charged",
        )
    )

    async with pool.connection() as conn, conn.cursor() as cur:
        await cur.execute(
            "SELECT title, body FROM kb_documents WHERE external_id = %s", ("kb-secret",)
        )
        row = await cur.fetchone()

    assert row is not None
    stored_title, stored_body = row
    assert stored_title.startswith("v1:") and "4242" not in stored_title
    assert stored_body.startswith("v1:") and "jane@example.com" not in stored_body

    hits = await repo.search(query="double charged", k=1)
    assert hits[0].body == "Customer jane@example.com was double charged"


@pytest.mark.asyncio
async def test_search_endpoint_returns_plaintext(client: AsyncClient) -> None:
    await client.post(
        "/kb/documents",
        headers=auth(),
        json={"external_id": "kb-1", "title": "Refunds", "body": "How to refund a charge"},
    )
    response = await client.get("/kb/search", headers=auth(), params={"q": "refund", "k": 1})
    assert response.status_code == 200
    assert response.json()["hits"][0]["body"] == "How to refund a charge"
