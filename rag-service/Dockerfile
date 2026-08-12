FROM python:3.13-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.11.28 /uv /usr/local/bin/uv

WORKDIR /app

COPY pyproject.toml ./
RUN uv sync --no-dev --no-install-project

COPY src ./src
RUN uv sync --no-dev

RUN useradd --create-home --uid 10001 rag \
    && chown -R rag:rag /app
USER rag

EXPOSE 8000

# Exec the venv's uvicorn directly rather than `uv run`: uv would re-resolve the
# environment at container start, which needs network access we don't have.
CMD ["/app/.venv/bin/uvicorn", "ticketmind_rag.main:app", "--host", "0.0.0.0", "--port", "8000"]
