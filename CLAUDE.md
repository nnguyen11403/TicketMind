# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

TicketMind is an AI-assisted support ticket triage system: users open tickets through a React frontend, a Spring Boot backend persists them in PostgreSQL, and a Python/FastAPI RAG service (LangChain + pgvector + Anthropic Claude) auto-categorises, prioritises, and suggests a resolution. See `README.md` for the user-facing description.

## Build plan & current progress

Work is shipped one feature branch at a time off `main`. Each step is a separate `feature/*` branch — when a step's tests are green it is pushed and the next branch starts on top.

| Step | Branch | Status |
|------|--------|--------|
| 1. Project scaffolding | `feature/scaffold` | merged |
| 2. Spring Boot backend bootstrap | `feature/backend-bootstrap` | merged |
| 3. Flyway schema (V1 core, V2 pgvector) | `feature/db-schema` | merged |
| 4. Auth (JWT + BCrypt + refresh rotation) | `feature/auth` | pushed, 58/58 tests green |
| 5. Ticket CRUD API | `feature/ticket-crud` | pushed, 68/68 tests green |
| 6. React frontend bootstrap | `feature/frontend-bootstrap` | pushed, 12/12 vitest green |
| 7. Ticket UI | `feature/ticket-ui` | pushed, 21/21 vitest green |
| 8. Python RAG service | `feature/rag-service` | **committed locally (not yet pushed)**, 37/37 pytest green (89% cov) |
| 9. Backend ↔ RAG wiring | — | **next** |
| 10. Full-stack docker-compose | — | pending |
| 11. CI/CD GitHub Actions | — | pending |

**Outstanding (out of Claude's control):** rotate the leaked Anthropic API key at console.anthropic.com — the previous key was committed to `.env` and must be revoked before the RAG service is wired up in step 9.

## Commands

```bash
# All tests (backend, Testcontainers — Docker must be running)
cd backend && ./mvnw test -B -ntp

# Single test class
cd backend && ./mvnw test -B -ntp -Dtest=AuthFlowIntegrationTest

# Single test method
cd backend && ./mvnw test -B -ntp -Dtest=AuthFlowIntegrationTest#registerLoginRefreshFlow

# Debug a failing integration test (full stack trace from the server side)
cd backend && ./mvnw test -B -ntp -Dtest=<Class>#<method> \
    -Dlogging.level.org.hibernate.SQL=DEBUG \
    -Dlogging.level.org.springframework.transaction=TRACE

# Frontend (needs VITE_API_BASE_URL — copy frontend/.env.example → .env)
cd frontend && npm install
cd frontend && npm run dev          # vite dev server on :3000
cd frontend && npm run build        # tsc -b && vite build
cd frontend && npm test             # vitest run (jsdom + MSW)
cd frontend && npm run typecheck    # tsc -b --noEmit
cd frontend && npm run lint         # oxlint src
cd frontend && npm run format       # prettier --write

# RAG service (uv; needs Docker for Testcontainers)
cd rag-service && uv sync --all-groups
cd rag-service && DOCKER_HOST=unix:///var/run/docker.sock uv run pytest
cd rag-service && uv run ruff check .          # lint
cd rag-service && uv run ruff format .         # format
cd rag-service && uv run uvicorn ticketmind_rag.main:app --reload --port 8000
```

**`DOCKER_HOST` is required for the RAG tests on macOS.** Docker Desktop's default
context points at `~/.docker/run/docker.sock`, and Testcontainers' Ryuk reaper
cannot bind-mount that path into a container (`mkdir /host_mnt/…/docker.sock:
operation not supported`). Pointing at `/var/run/docker.sock` — the symlink Docker
Desktop special-cases — makes it work. The Java Testcontainers setup is unaffected.

## Tech stack (as actually built)

- **Java 21 LTS**, Spring Boot **4.1.0**, Maven wrapper
- **PostgreSQL** via Testcontainers using `pgvector/pgvector:pg16` (the vector extension is in V2, so any test container must be the pgvector image, not stock postgres)
- Spring Security stateless JWT (HS256) via **JJWT 0.12.6**
- **BCrypt** via `DelegatingPasswordEncoder` — prefix-prefixed hashes (`{bcrypt}$2a$...`) so the algorithm can be upgraded in place without a migration
- **Bucket4j 8.10.1** for per-IP rate limiting
- **Flyway** with `ddl-auto=validate` (entities must match V1/V2 exactly)
- **JPA Auditing** for `created_at` / `updated_at`
- **React 19 + TypeScript 6 + Vite 8** frontend with React Router v7, TanStack Query v5, React Hook Form + Zod, Tailwind CSS v4
- Frontend tests: Vitest 3 + Testing Library + MSW 2 (jsdom); MSW's `onUnhandledRequest: 'error'` catches missing handlers
- RAG service: **Python 3.13** + FastAPI, managed with **uv**; `psycopg` 3 async pool + `pgvector`; `langchain-anthropic` for chat (default model **`claude-opus-5`**), `voyageai` for embeddings (`voyage-3`, 1024-dim); Pydantic Settings for config; **ruff** for lint + format
- RAG tests: pytest + `pytest-asyncio` (auto mode) + `testcontainers` on `pgvector/pgvector:pg16`, `asgi-lifespan` for the FastAPI lifespan, `--cov-fail-under=80`

## Architecture decisions baked in

These are non-obvious choices that future code must keep consistent.

**Auth (step 4):**

- Refresh tokens are 256-bit `SecureRandom` strings; only their SHA-256 hash is stored in `refresh_tokens.token_hash`. The raw token never lands in Postgres.
- Refresh rotation on every `/auth/refresh`: the old token is revoked, a new one is issued, `replaced_by_id` records the chain. Re-using a token that was already rotated triggers **family-wide revocation** of every active token for that user (signals stolen refresh token).
- The family-revocation path needs both `noRollbackFor = InvalidRefreshTokenException.class` on `RefreshTokenService.rotate` **and** `AuthService.refresh` deliberately **not** annotated `@Transactional`. If you re-add `@Transactional` to `refresh` it will silently break reuse detection — the outer transaction rolls back the bulk UPDATE that revokes the family.
- Refresh cookie: `tm_refresh`, `HttpOnly` + `Secure` + `SameSite=Strict` + `Path=/auth`. Path is scoped to `/auth` so the cookie is not attached to data endpoints — no CSRF surface on `/tickets`, etc.
- Lockout: 5 failed login attempts → 15 min `locked_until`. The failed-attempt counter increment runs in a separate bean (`LoginAttemptRecorder`) with `Propagation.REQUIRES_NEW` so that `InvalidCredentialsException` rolling back the outer login transaction does **not** erase the increment.
- Unknown-email login path runs a decoy `passwordEncoder.matches` against a fixed dummy hash before throwing, to flatten the user-enumeration timing side channel.
- Per-IP rate limit (Bucket4j) on `/auth/register`, `/auth/login`, `/auth/refresh`. Limits are wired through `app.auth.rate-limit.*` in `application.yml`. `Retry-After` is surfaced via the API error envelope.
- The IP audit column on `refresh_tokens.ip_address` is `VARCHAR(45)` — we tried `INET` first but the JPA binding round-trip is fragile and we never query as a network type.

**Error envelope:**

- All domain exceptions extend `com.ticketmind.backend.common.exception.ApiException` (status + stable machine code). `GlobalExceptionHandler` translates them to `ApiError { status, code, fieldErrors? }`. Exception messages are **never** returned to the client — only the code. The generic `Exception` handler logs the real cause and returns `internal_error` with HTTP 500, so internal class names / messages don't leak.
- `NoResourceFoundException` has an explicit handler returning 404 `not_found`; without it, Spring Boot 4 routes it through the generic handler and returns 500.
- The class was previously named `AuthException` but was renamed to `ApiException` during step 5 so non-auth domains (tickets) can share the same machinery.

**Security wiring:**

- `SecurityConfig` is stateless: no sessions, CSRF disabled (we use Bearer tokens for data endpoints), form login / HTTP basic / Spring's logout all disabled. HSTS 1y `includeSubDomains`, frame options DENY, referrer policy NO_REFERRER.
- Public matchers: `/actuator/**`, `/error`, POST `/auth/register|login|refresh|logout`. Everything else is `authenticated()`.
- `JwtAuthenticationFilter` reads `Authorization: Bearer <token>` and seats a `JwtPrincipal(id, email, role)` as the `Authentication.principal`. Use `@AuthenticationPrincipal JwtPrincipal principal` in controllers.
- `@EnableMethodSecurity` is on; method-level `@PreAuthorize("hasRole('AGENT')")` etc. works.

**Frontend (step 6):**

- Access token lives in module memory only (`src/api/tokenStore.ts`) — never in localStorage/sessionStorage. XSS should not be able to harvest a persistent credential. Refresh survives page reload because the backend's HttpOnly `tm_refresh` cookie is still valid; the app performs a silent `/auth/refresh` on mount (`AuthProvider`).
- `apiFetch` (`src/api/client.ts`) attaches `Authorization: Bearer …` when a token exists, transparently refreshes-and-retries **once** on 401, and clears the token when refresh itself fails. Concurrent refreshes are serialised into a single in-flight promise — many stale requests firing at once would otherwise walk into the backend's family-revocation trap.
- Auth endpoints use `skipAuth: true` so `/auth/login`/`/auth/register`/`/auth/refresh` never recurse through the 401-refresh path.
- API errors are decoded through the backend's `{ status, code, fieldErrors? }` envelope into an `ApiError` class; user-facing copy is centralised in `src/lib/errorMessages.ts` (keyed by `code`, never showing raw messages). `Retry-After` seconds land on `ApiError.retryAfterSeconds` for rate-limit UI.
- Form validation mirrors the backend rules with Zod (via React Hook Form + `@hookform/resolvers/zod`). The registration form's 12-char + letter + digit check exists so users see feedback before the network round-trip; the backend still enforces it.
- The Prod SPA is served from Vite's `dist/`. In dev, the Vite server runs on `:3000` (matching `CORS_ALLOWED_ORIGINS` in `.env.example`); the Spring backend runs on `:8080`.

**Frontend test conventions:**

- `vitest.config.ts` is separate from `vite.config.ts` because Vitest 3 still bundles a Vite 5 API — the React plugin type from Vite 8 conflicts, so `vitest.config.ts` casts `react()` to `never`. This is intentional; both configs work at runtime.
- MSW handlers must be registered per-test with `server.use(...)`. Global setup uses `onUnhandledRequest: 'error'` — any un-mocked request fails loudly. `tokenStore.clear()` runs in `afterEach`.
- `renderWithProviders` (`src/test/renderApp.tsx`) wraps in `MemoryRouter` + `QueryClientProvider` + `AuthProvider`. `AuthProvider` fires a silent `/auth/refresh` on mount, so tests must mock that endpoint (return 401 for anonymous state).
- `.env.test` sets `VITE_API_BASE_URL=http://api.test` — MSW handlers key off that same base.

**RAG service (step 8):**

- The service owns its own `kb_documents` table, created with `IF NOT EXISTS` at startup — it does **not** read the backend's `ticket_embeddings`. Flyway lives with the Java service; a cross-service Flyway migration would couple the two deploys, and a schema mistake here would be able to corrupt ticket/auth data. The embedding dimension is templated into the DDL from `RAG_EMBEDDING_DIM` so a model swap is a config change plus a re-embed.
- Vectors are bound as `pgvector.Vector`, never as plain lists. A bare `list[float]` is sent as `float8[]`, which Postgres can only coerce when a target column supplies the type — so `INSERT` silently works while `embedding <=> %s` in the search query fails with "No operator matches the given name and argument types".
- `AnthropicChat` passes **no `temperature`**. Claude Opus 5 (and every Opus 4.7+ model) rejects sampling parameters with a 400; determinism is steered by the prompt instead.
- Auth is a single shared secret on `X-Internal-Key`, compared with `hmac.compare_digest`. Missing and wrong keys both return `401 unauthorized` — inside the compose network, a foothold on another container shouldn't be able to enumerate whether a header is needed at all.
- `Embedder` and `ChatModel` are `Protocol`s with `Fake*` implementations, so the whole HTTP surface is testable without a Voyage/Anthropic key. `FakeEmbedder` hashes the input to a unit vector, which makes cosine ordering meaningful and deterministic.
- Long-lived objects (pool, embedder, chat, repo, triage service) live on `app.state`, wired in the lifespan, and dependency callables pull them off the `Request`. Tests pre-seed `app.state` before entering the lifespan, so the lifespan only builds what isn't already there — and only closes the pool it opened (`app.state._owns_pool`).
- The LLM contract is a bare JSON object. `_extract_json` pulls the first `{...}` block out of the reply because real replies occasionally wrap in prose or fences; anything unparseable raises `TriageError` → **502 `upstream_error`**, so the backend treats it as an upstream fault rather than a bad request. Citation scores come from the retrieval results, never from the model's own confidence claims.
- The Dockerfile execs `/app/.venv/bin/uvicorn` directly. `uv run` would re-resolve the environment at container start, which needs network access the container doesn't have.

## Test conventions

- **Integration tests use Testcontainers, not mocks.** The `TestcontainersConfiguration` spins up a real pgvector container; tests like `AuthFlowIntegrationTest` hit the real DB. Do **not** introduce `@MockBean` on repositories — the original CLAUDE.md guidance to mock the DB is superseded.
- Tests must annotate `@ActiveProfiles("test")`. The test profile sets a fixed `JWT_SECRET`, dials BCrypt strength to 4 (production minimum is 12), and uses a known schema.
- Do **not** put `@Transactional` on integration test classes that exercise services using `Propagation.REQUIRES_NEW` (e.g. failed-login bookkeeping, family revocation). Spring's test-class transaction rolls back the outer transaction, but REQUIRES_NEW inner transactions can't see uncommitted outer state — tests fail with confusing "user not found" errors. Use explicit `@BeforeEach` cleanup via `repository.deleteAllInBatch()` instead.
- `ObjectMapper` is **not** an autowireable bean in `@SpringBootTest` slices here — instantiate `new ObjectMapper()` in tests.
- Run `./mvnw test` after **every** change. Don't push a branch with a red bar.
- RAG service: same rule with `uv run pytest`. One pgvector container per session (`pg_container` is session-scoped); the `pool` fixture truncates `kb_documents` between tests rather than recreating the schema. `pythonpath = ["."]` in `pyproject.toml` is what makes `from tests.conftest import ...` resolve. Coverage is gated at 80%.

## Code style

- Java: explicit braces, descriptive identifiers, no needless comments. A comment exists only when the *why* is non-obvious — a hidden constraint, a subtle invariant, a workaround for a specific Spring/Hibernate quirk. Don't comment what the code says.
- Python: `from __future__ import annotations`, full type hints on public functions, module docstrings that explain the *why* of the module. Same comment rule as Java. `ruff format` is authoritative — don't hand-wrap lines it would rejoin.
- Don't introduce premature abstractions. Three similar lines is fine.
- Don't add backwards-compat shims, deprecated re-exports, or `// removed` markers when deleting code.
- Don't write `_unused` helpers, mock layers, or "ready for X" scaffolding unless the next step actually uses them.

## Git workflow

- One feature per branch; branch names `feature/<slice>`.
- Commit messages are imperative ("add", not "added"), with a body that explains *why* and any non-obvious decision. The auth commit on `feature/auth` is the reference for length / depth.
- Each PR is a single coherent slice — the auth branch is large because the slice is large, not because changes are batched.
- Don't `--no-verify`, don't force-push. If a hook fails, fix the underlying issue.

## Where step 8 (Python RAG service) left off

`feature/rag-service` is committed locally; the push is still outstanding (github.com:22 was unreachable from the dev machine — retry `git push -u origin feature/rag-service`). **Shipped** (`rag-service/`):

- `pyproject.toml` — uv project, Python >=3.13, hatchling build over `src/ticketmind_rag`, pytest (asyncio auto mode, `pythonpath = ["."]`, `--cov-fail-under=80`) and ruff (E/F/W/I/B/UP/SIM/N/ASYNC/RUF, line-length 100) config. `B008` is ignored in `deps.py`/`main.py` — `Depends(...)` in an argument default is the FastAPI convention, not the mutable-default bug.
- `config.py` — `Settings` via pydantic-settings; secrets wrapped in `SecretStr`; `get_settings` is `lru_cache`d so tests can `cache_clear()`.
- `db.py` — async `psycopg_pool` pool + `ensure_schema` (creates the `vector` extension, `kb_documents`, and an HNSW cosine index).
- `embeddings.py` / `llm.py` — `Embedder` and `ChatModel` Protocols with `VoyageEmbedder`/`AnthropicChat` and `FakeEmbedder`/`FakeChat`.
- `kb.py` — upsert-by-`external_id` and cosine k-NN search returning `1 - (embedding <=> $1)` as a similarity score.
- `prompts.py` / `triage.py` — system prompt + user-prompt builder, and the retrieve → prompt → parse pipeline with `TriageError`.
- `main.py` — `GET /health` (public), `POST /kb/documents`, `GET /kb/search`, `POST /triage` (all behind `X-Internal-Key`), plus the `TriageError` → 502 handler.
- `Dockerfile`, `.env.example`, `.gitignore`.
- 37/37 pytest green, 89% coverage: config/secret-redaction, schema validation, embedder determinism, auth on every protected route, KB persistence + retrieval ordering, and triage happy path, priority normalisation, malformed-citation filtering, and the 502 path.

**Step 8 is closed.** Step 9 wires the Spring backend to this service: call `POST /triage` on ticket creation with the `X-Internal-Key` secret, persist `category`/`priority`/`suggested_resolution` back onto the ticket, and mirror resolved tickets into `POST /kb/documents` so retrieval improves over time. Rotate the leaked Anthropic key first.

## Out of scope

Kubernetes, Terraform, and Kafka are intentionally not part of this build. Keep infra additions limited to Docker Compose unless explicitly asked to expand scope.
