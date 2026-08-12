# syntax=docker/dockerfile:1.7

# ---------- build ----------
# build-essential and uv are only needed to resolve and compile the venv. They
# stay in this stage; shipping them was most of a 1.76GB image.
FROM python:3.13-slim AS build

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.11.28 /uv /usr/local/bin/uv

WORKDIR /app

# Dependencies first so a source-only change doesn't re-resolve the world.
COPY pyproject.toml ./
RUN uv sync --no-dev --no-install-project

COPY src ./src
RUN uv sync --no-dev

# ---------- runtime ----------
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# The venv hardcodes absolute paths, so /app/.venv must be identical in both
# stages. Both stages share the same base image, so the compiled extensions
# copied here match the runtime's libc.
COPY --from=build --chown=10001:10001 /app/.venv /app/.venv
COPY --from=build --chown=10001:10001 /app/src /app/src

RUN useradd --create-home --uid 10001 rag
USER rag

EXPOSE 8000

# No curl in this image — the healthcheck uses the interpreter that is already
# here, which keeps the runtime stage free of any apt install at all.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD ["/app/.venv/bin/python", "-c", \
       "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/health', timeout=4).status==200 else 1)"]

# Exec the venv's uvicorn directly rather than `uv run`: uv would re-resolve the
# environment at container start, which needs network access we don't have.
CMD ["/app/.venv/bin/uvicorn", "ticketmind_rag.main:app", "--host", "0.0.0.0", "--port", "8000"]
