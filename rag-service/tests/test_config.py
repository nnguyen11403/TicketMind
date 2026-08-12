import pytest
from pydantic import ValidationError

from ticketmind_rag.config import Settings, get_settings


def test_settings_load_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.setenv("RAG_INTERNAL_API_KEY", "k")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "a")
    monkeypatch.setenv("VOYAGE_API_KEY", "v")
    monkeypatch.setenv("RAG_RETRIEVAL_K", "7")

    get_settings.cache_clear()
    try:
        s = get_settings()
    finally:
        get_settings.cache_clear()

    assert s.database_url == "postgresql://x"
    assert s.internal_api_key.get_secret_value() == "k"
    assert s.anthropic_api_key.get_secret_value() == "a"
    assert s.voyage_api_key.get_secret_value() == "v"
    assert s.retrieval_k == 7
    # Defaults are wired.
    assert s.chat_model == "claude-opus-5"
    assert s.embedding_model == "voyage-3"
    assert s.embedding_dim == 1024


def test_settings_reject_out_of_range_retrieval_k(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.setenv("RAG_INTERNAL_API_KEY", "k")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "a")
    monkeypatch.setenv("VOYAGE_API_KEY", "v")
    monkeypatch.setenv("RAG_RETRIEVAL_K", "0")

    with pytest.raises(ValidationError):
        Settings()  # type: ignore[call-arg]


def test_secrets_do_not_leak_in_repr(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.setenv("RAG_INTERNAL_API_KEY", "super-secret-key")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-super-secret")
    monkeypatch.setenv("VOYAGE_API_KEY", "pk-voyage-super-secret")

    s = Settings()  # type: ignore[call-arg]
    rendered = repr(s)
    assert "super-secret-key" not in rendered
    assert "sk-ant-super-secret" not in rendered
    assert "pk-voyage-super-secret" not in rendered
