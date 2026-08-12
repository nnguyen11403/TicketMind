"""Runtime configuration.

Loaded via ``pydantic-settings`` from environment variables (or a ``.env`` file
next to the process). Secrets are wrapped in :class:`SecretStr` so they can't
accidentally land in logs or exception messages.
"""

from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All environment-driven configuration for the RAG service."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    service_host: str = Field(default="0.0.0.0", alias="RAG_SERVICE_HOST")
    service_port: int = Field(default=8000, alias="RAG_SERVICE_PORT")

    database_url: str = Field(alias="DATABASE_URL")

    internal_api_key: SecretStr = Field(alias="RAG_INTERNAL_API_KEY")

    anthropic_api_key: SecretStr = Field(alias="ANTHROPIC_API_KEY")
    chat_model: str = Field(default="claude-opus-5", alias="RAG_CHAT_MODEL")

    voyage_api_key: SecretStr = Field(alias="VOYAGE_API_KEY")
    embedding_model: str = Field(default="voyage-3", alias="RAG_EMBEDDING_MODEL")
    # voyage-3 emits 1024-dim vectors; must match the vector(1024) column type
    # in the V2 Flyway migration and in kb_documents below.
    embedding_dim: int = Field(default=1024, alias="RAG_EMBEDDING_DIM")

    retrieval_k: int = Field(default=5, alias="RAG_RETRIEVAL_K", ge=1, le=50)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return a process-wide singleton :class:`Settings`.

    ``lru_cache`` gives us the singleton for free and — importantly — lets
    tests call ``get_settings.cache_clear()`` between cases where they want to
    override the environment.
    """

    return Settings()  # type: ignore[call-arg]
