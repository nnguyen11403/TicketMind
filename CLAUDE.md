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
| 8. Python RAG service | `feature/rag-service` | pushed, 41/41 pytest green (89% cov) |
| 9. Backend ↔ RAG wiring | `feature/rag-wiring` | pushed, 91/91 backend + 67/67 vitest + 41/41 pytest green |
| 10. Full-stack docker-compose | — | **next** |
| 11. CI/CD GitHub Actions | — | pending |

**Anthropic API key — investigated 2026-08-12, not leaked via this repo.** An earlier note here claimed the key had been committed to `.env` and treated it as a blocker for step 9. That was wrong. Verified:

- `.env` has never appeared in any commit on any ref, and is gitignored (`.gitignore:2`).
- The key value appears in **zero** git objects — all 238 blobs scanned, including unreachable ones left behind by amended commits.
- Every `sk-ant-` string in history is a `sk-ant-replace-me` placeholder in a `.env.example`, plus a fake in `rag-service/tests/test_config.py`.
- GitHub secret scanning **and** push protection are enabled on this public repo and report no alerts, past or present. Anthropic is a scanning partner, so a real key in any pushed commit would have been flagged — and push protection would have refused the push in the first place.

Residual risk is non-git only (pasted into a chat, a screenshot, a log). Rotation is still cheap insurance and the only action that actually neutralises a key; if you rotate, `.env` is the only file to update. Check Console usage history if you want to know whether it was ever used by anyone else.

## Commands

```bash
# One-time per clone: enable the repo's secret-scanning pre-commit hook.
git config core.hooksPath .githooks

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

**Backend ↔ RAG wiring (step 9):**

- Triage is **asynchronous and best-effort**. `TicketService` publishes `TicketCreatedEvent` / `TicketResolvedEvent`; `RagTicketListener` handles them `@Async("ragTaskExecutor")` on `AFTER_COMMIT`. `POST /tickets` therefore answers 201 with `category`/`priority`/`triagedAt` still null — the client sees them appear on a later fetch. A RAG outage never fails ticket submission.
- `AFTER_COMMIT` is load-bearing twice over: triage must not run against a transaction that then rolls back, and the write-back reads the ticket on a second connection, which cannot see an uncommitted row.
- `TicketTriageWriter` is a separate bean from `TicketTriageService` for the same reason `LoginAttemptRecorder` is separate from `AuthService` — the orchestration must not hold a DB connection across a multi-second Claude call, but the ticket update and its TRIAGED history row must land in one transaction. A `@Transactional` method on the same class would self-invoke past the proxy and split them.
- **Priority vocabulary is owned by the database.** `tickets_priority_check` in V1 allows `LOW/MEDIUM/HIGH/CRITICAL` only. The RAG service originally emitted `URGENT`; that was fixed in step 9, and `TicketTriageWriter` still rejects unknown values rather than letting them reach the constraint. `TicketTriageServiceIntegrationTest` asserts every enum value round-trips, so a future drift fails loudly.
- The TRIAGED history row has a **null actor** — no human performed it, and `ticket_history.actor_id` is nullable for exactly this case. The frontend `Timeline` renders that as "System".
- `app.rag.enabled` defaults to **false** so a bare `mvnw spring-boot:run` works without a RAG key. When it is true, a blank `app.rag.internal-key` fails startup — silently sending unauthenticated requests would 401 on every call and look like "triage just doesn't work".
- The RAG executor's queue is bounded and overflow is **dropped, not run on the caller**: the caller is the thread that just committed a ticket, so `CallerRunsPolicy` would turn a RAG backlog into user-visible latency on `POST /tickets`.
- `RagClient` truncates title/body to the RAG service's limits (256 / 32000). Ticket descriptions are `TEXT` and would otherwise 422.
- Resolved tickets are mirrored to `POST /kb/documents` keyed by `external_id = ticket UUID`. Upsert-by-external-id makes reopen-then-re-resolve idempotent.

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

- The detail page polls `['ticket', id]` every 3s while `triagedAt` is null, and stops after 2 minutes (`TRIAGE_POLL_WINDOW_MS`) so a ticket the RAG service never triaged doesn't poll forever. Same window drives the "Analysing this ticket…" hint. Without this the async triage would only appear on a manual refresh.

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
- `.githooks/pre-commit` blocks staged content shaped like a live credential (Anthropic, OpenAI, Voyage, GitHub, AWS, PEM blocks). It is versioned but `core.hooksPath` is local config, so each clone runs `git config core.hooksPath .githooks` once. The patterns require real key length, so `sk-ant-replace-me` and the `sk-ant-super-secret` fixture in `rag-service/tests/test_config.py` pass — if a rule fires on a fixture, shorten the fixture rather than loosening the rule.

## Where step 9 (Backend ↔ RAG wiring) left off

`feature/rag-wiring` is pushed, branched off `feature/rag-service` (also
pushed). **Shipped:**

- `backend/.../rag/` — `RagProperties` (`app.rag.*`), `RagClientConfig` (RestClient with a `JdkClientHttpRequestFactory`, separate connect/read timeouts, `X-Internal-Key` default header), `RagClient` (best-effort, never throws), `TicketTriageService` (orchestration, no transaction held across the HTTP call), `TicketTriageWriter` (`@Transactional` write-back), `RagTicketListener` (`@Async` + `AFTER_COMMIT`, `@ConditionalOnProperty`), `RagAsyncConfig` (bounded `rag-` pool that drops on overflow), and the three wire DTOs.
- `TicketService` publishes `TicketCreatedEvent` on create and `TicketResolvedEvent` on the RESOLVED transition.
- Config: `app.rag.*` in `application.yml` (disabled by default), disabled in `application-test.yml`, `RAG_ENABLED` / `RAG_SERVICE_URL` added to the root `.env.example` and the commented backend block in `docker-compose.yml`.
- `rag-service`: priority vocabulary changed `URGENT` → `CRITICAL` to match the backend enum and the V1 check constraint, with a parametrised regression test.
- Frontend: detail page polls until `triagedAt` lands and shows an "Analysing this ticket…" hint; `Timeline` names the category and priority on a TRIAGED row.
- Green: **91/91 backend** (was 68 — +9 `RagClientTest`, +7 `TicketTriageServiceIntegrationTest`, +3 `RagPropertiesTest`, +2 `RagIntegrationWiringTest`, +2 in `TicketControllerIntegrationTest`), **67/67 vitest** (was 63), **41/41 pytest** (was 37).

**Step 9 is closed.** Step 10 stands up the full docker-compose: uncomment the `backend`, `rag-service`, and `frontend` services (the backend block already carries `RAG_ENABLED`/`RAG_SERVICE_URL`/`RAG_INTERNAL_API_KEY`), and add the cross-service smoke test that this slice could not cheaply write. Rotate the leaked Anthropic key before anything actually calls Claude.

**Not done in step 9 (deliberate):** no end-to-end test runs the real Python service against the real backend — the RAG service is stubbed at the HTTP boundary on the Java side, and the two contracts are kept honest by matching tests on each side rather than by a shared fixture. Step 10 (docker-compose) is where a genuine cross-service smoke test becomes cheap.

## Out of scope

Kubernetes, Terraform, and Kafka are intentionally not part of this build. Keep infra additions limited to Docker Compose unless explicitly asked to expand scope.
