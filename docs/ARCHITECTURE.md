# Architecture notes

Design decisions and constraints that are not obvious from the code. Read this
before changing anything in the auth, triage, or compose wiring.

## Project

TicketMind is an AI-assisted support ticket triage system: users open tickets through a React frontend, a Spring Boot backend persists them in PostgreSQL, and a Python/FastAPI RAG service (LangChain + pgvector + Anthropic Claude) auto-categorises, prioritises, and suggests a resolution. See `README.md` for the user-facing description.

## Status

| Area | State |
|------|-------|
| Auth (JWT, BCrypt, refresh rotation) | done |
| Ticket CRUD + history audit | done |
| React frontend | done |
| RAG service (FastAPI, pgvector, Claude) | done |
| Backend to RAG wiring | done |
| Role management | done |
| docker-compose | done |
| CI (GitHub Actions) | done |
| Pre-deployment hardening (RLS, encryption at rest, least-privilege DB roles, CSP, dependency scanning) | done |
| Live deployment | not started |

## Commands

```bash
# One-time per clone: enable the repo's secret-scanning pre-commit hook.
git config core.hooksPath .githooks

# All tests (backend, Testcontainers, Docker must be running)
cd backend && ./mvnw test -B -ntp

# Single test class
cd backend && ./mvnw test -B -ntp -Dtest=AuthFlowIntegrationTest

# Single test method
cd backend && ./mvnw test -B -ntp -Dtest=AuthFlowIntegrationTest#registerLoginRefreshFlow

# Debug a failing integration test (full stack trace from the server side)
cd backend && ./mvnw test -B -ntp -Dtest=<Class>#<method> \
    -Dlogging.level.org.hibernate.SQL=DEBUG \
    -Dlogging.level.org.springframework.transaction=TRACE

# Frontend (needs VITE_API_BASE_URL, copy frontend/.env.example → .env)
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

# Full stack (needs a root .env, copy .env.example and fill in the API keys)
docker compose up -d --build

# Smoke test. Source the .env first: the database-level checks (encryption at
# rest, RLS, the RAG role's blast radius) need the Postgres credentials, and
# skip themselves with a NOTE rather than failing when they are absent.
set -a; . ./.env; set +a; ./scripts/smoke-test.sh
docker compose down -v           # -v also drops the postgres volume
docker compose logs -f backend   # or rag-service / frontend / postgres
```

**`DOCKER_HOST` is required for the RAG tests on macOS.** Docker Desktop's default
context points at `~/.docker/run/docker.sock`, and Testcontainers' Ryuk reaper
cannot bind-mount that path into a container (`mkdir /host_mnt/…/docker.sock:
operation not supported`). Pointing at `/var/run/docker.sock`, the symlink Docker
Desktop special-cases. Makes it work. The Java Testcontainers setup is unaffected.

## Tech stack (as actually built)

- **Java 21 LTS**, Spring Boot **4.1.0**, Maven wrapper
- **PostgreSQL** via Testcontainers using `pgvector/pgvector:pg16` (the vector extension is in V2, so any test container must be the pgvector image, not stock postgres)
- Spring Security stateless JWT (HS256) via **JJWT 0.12.6**
- **BCrypt** via `DelegatingPasswordEncoder`. Prefix-prefixed hashes (`{bcrypt}$2a$...`) so the algorithm can be upgraded in place without a migration
- **Bucket4j 8.10.1** for per-IP rate limiting
- **Flyway** with `ddl-auto=validate` (entities must match V1/V2 exactly)
- **JPA Auditing** for `created_at` / `updated_at`
- **React 19 + TypeScript 6 + Vite 8** frontend with React Router v7, TanStack Query v5, React Hook Form + Zod, Tailwind CSS v4
- Frontend tests: Vitest 3 + Testing Library + MSW 2 (jsdom); MSW's `onUnhandledRequest: 'error'` catches missing handlers
- RAG service: **Python 3.13** + FastAPI, managed with **uv**; `psycopg` 3 async pool + `pgvector`; `langchain-anthropic` for chat (default model **`claude-opus-5`**), `voyageai` for embeddings (`voyage-3`, 1024-dim); Pydantic Settings for config; **ruff** for lint + format
- RAG tests: pytest + `pytest-asyncio` (auto mode) + `testcontainers` on `pgvector/pgvector:pg16`, `asgi-lifespan` for the FastAPI lifespan, `--cov-fail-under=80`

## Architecture decisions baked in

These are non-obvious choices that future code must keep consistent.

**Auth:**

- Refresh tokens are 256-bit `SecureRandom` strings; only their SHA-256 hash is stored in `refresh_tokens.token_hash`. The raw token never lands in Postgres.
- Refresh rotation on every `/auth/refresh`: the old token is revoked, a new one is issued, `replaced_by_id` records the chain. Re-using a token that was already rotated triggers **family-wide revocation** of every active token for that user (signals stolen refresh token).
- The family-revocation path needs both `noRollbackFor = InvalidRefreshTokenException.class` on `RefreshTokenService.rotate` **and** `AuthService.refresh` deliberately **not** annotated `@Transactional`. If you re-add `@Transactional` to `refresh` it will silently break reuse detection. The outer transaction rolls back the bulk UPDATE that revokes the family.
- **`SPRING_PROFILES_ACTIVE` must not be `dev` in a real deployment.** The dev profile sets `app.cookie.secure: false` so `http://localhost` works, and turns on DEBUG logging. Any other value restores Secure cookies, INFO logging, and the https channel rule. `.env.example` defaults to `dev` because that is what makes `docker compose up` work on a laptop, which is exactly why it is worth stating here.
- Refresh cookie: `tm_refresh`, `HttpOnly` + `Secure` + `SameSite=Strict` + `Path=/auth`. Path is scoped to `/auth` so the cookie is not attached to data endpoints, no CSRF surface on `/tickets`, etc.
- Lockout: 5 failed login attempts → 15 min `locked_until`. The failed-attempt counter increment runs in a separate bean (`LoginAttemptRecorder`) with `Propagation.REQUIRES_NEW` so that `InvalidCredentialsException` rolling back the outer login transaction does **not** erase the increment.
- Unknown-email login path runs a decoy `passwordEncoder.matches` against a fixed dummy hash before throwing, to flatten the user-enumeration timing side channel.
- Per-IP rate limit (Bucket4j) on `/auth/register`, `/auth/login`, `/auth/refresh`. Limits are wired through `app.auth.rate-limit.*` in `application.yml`. `Retry-After` is surfaced via the API error envelope.
- The IP audit column on `refresh_tokens.ip_address` is `VARCHAR(45)`. We tried `INET` first but the JPA binding round-trip is fragile and we never query as a network type.

**Backend ↔ RAG wiring:**

- Triage is **asynchronous and best-effort**. `TicketService` publishes `TicketCreatedEvent` / `TicketResolvedEvent`; `RagTicketListener` handles them `@Async("ragTaskExecutor")` on `AFTER_COMMIT`. `POST /tickets` therefore answers 201 with `category`/`priority`/`triagedAt` still null. The client sees them appear on a later fetch. A RAG outage never fails ticket submission.
- `AFTER_COMMIT` is load-bearing twice over: triage must not run against a transaction that then rolls back, and the write-back reads the ticket on a second connection, which cannot see an uncommitted row.
- `TicketTriageWriter` is a separate bean from `TicketTriageService` for the same reason `LoginAttemptRecorder` is separate from `AuthService`, the orchestration must not hold a DB connection across a multi-second Claude call, but the ticket update and its TRIAGED history row must land in one transaction. A `@Transactional` method on the same class would self-invoke past the proxy and split them.
- **Priority vocabulary is owned by the database.** `tickets_priority_check` in V1 allows `LOW/MEDIUM/HIGH/CRITICAL` only. `TicketTriageWriter` rejects unknown values rather than letting them reach the constraint. `TicketTriageServiceIntegrationTest` asserts every enum value round-trips, so a future drift fails loudly.
- The TRIAGED history row has a **null actor**. No human performed it, and `ticket_history.actor_id` is nullable for exactly this case. The frontend `Timeline` renders that as "System".
- `app.rag.enabled` defaults to **false** so a bare `mvnw spring-boot:run` works without a RAG key. When it is true, a blank `app.rag.internal-key` fails startup. Silently sending unauthenticated requests would 401 on every call and look like "triage just doesn't work".
- The RAG executor's queue is bounded and overflow is **dropped, not run on the caller**: the caller is the thread that just committed a ticket, so `CallerRunsPolicy` would turn a RAG backlog into user-visible latency on `POST /tickets`.
- `RagClient` truncates title/body to the RAG service's limits (256 / 32000). Ticket descriptions are `TEXT` and would otherwise 422.
- Resolved tickets are mirrored to `POST /kb/documents` keyed by `external_id = ticket UUID`. Upsert-by-external-id makes reopen-then-re-resolve idempotent.

**Docker Compose:**

- **The JDK HttpClient must be pinned to HTTP/1.1** (`RagClientConfig.buildHttpClient`). It defaults to `HTTP_2`, which sends an HTTP/1.1 upgrade handshake that uvicorn's h11 parser rejects with "Invalid HTTP request received", the body never reaches FastAPI, which then answers 422 for a missing body. Every backend test passed anyway, because `MockRestServiceServer` intercepts above the socket. This was found only by running the real stack, and it is the reason `scripts/smoke-test.sh` exists.
- Startup is serialised **postgres → backend → rag-service**. Both the backend's Flyway V2 and the RAG service run `CREATE EXTENSION IF NOT EXISTS vector` against the same database; issuing that concurrently from two connections can fail with a duplicate-key or "tuple concurrently updated" error. Letting Flyway win makes the RAG service's copy an unconditional no-op. This is why `rag-service` waits on `backend`, not just on `postgres`.
- `VITE_API_BASE_URL` is a **build arg, not a runtime env var**. Vite inlines `VITE_*` at build time, so the value is baked into the bundle and the frontend image is environment-specific. It must be the address the *browser* can reach (`http://localhost:8080`), never a compose service name. The request is made from the user's machine, where `backend` does not resolve. Changing the API host means rebuilding the image.
- `RAG_SERVICE_URL` is the mirror image: compose hardcodes `http://rag-service:8000` because that call is made from inside the network. The `.env.example` entry only applies when running the backend on the host.
- nginx serves the SPA with a `try_files … /index.html` fallback, so a deep link like `/tickets/<uuid>` survives a refresh. `/assets/` is immutable-cached (Vite content-hashes those names) while `index.html` is `no-cache`. Cache index.html and browsers never learn to ask for the new hashed bundles.
- **nginx's `add_header` does not merge across levels.** A `location` block that declares even one `add_header` of its own stops inheriting *every* `add_header` from the server block. Both `/index.html` and `/assets/` set `Cache-Control`, so the security headers were silently absent from every real response while being plainly present in the config file. They now live in `frontend/security-headers.conf`, `include`d by each location as well as at server level. Any new location that sets a header has to include it too. This was found by the smoke test, not by reading the config.
- The frontend CSP allows `'unsafe-inline'` for **style-src only**: Vite emits a small inline style block for the initial paint and React sets inline styles at runtime. `script-src` gets no such exemption — the whole bundle is a hashed file under `/assets`, so `'self'` covers it.
- `connect-src` has to name the API origin explicitly, because it is a different port and therefore a different origin. `${CSP_CONNECT_SRC}` is substituted **at image build time** from the same `VITE_API_BASE_URL` build arg the bundle was compiled against, not at container start: nginx would otherwise read `${CSP_CONNECT_SRC}` as one of its own variables and refuse to load the config. The Dockerfile runs `nginx -t` afterwards so a malformed policy fails the build rather than the deploy.
- The https redirect is gated on `X-Forwarded-Proto: http` and exempts `/healthz`, because the compose healthcheck speaks plain http and would otherwise follow a 301 to a port nothing is listening on.
- Postgres publishes on `127.0.0.1` only. Every service reaches it over the compose network; the mapping exists so a developer can attach psql, and published on `0.0.0.0` it would put the owning credential on the host's public interfaces.
- `CORS_ALLOWED_ORIGINS` must track `FRONTEND_PORT`. They are separate variables and nothing validates that they agree; a mismatch blocks every API call from the browser.

**CI:**

- `.github/workflows/ci.yml` runs `backend`, `frontend`, `rag-service` and `dependencies` in parallel, then `stack` (compose build + `scripts/smoke-test.sh`) gated on the first three. The stack job is the expensive one, so it only runs once the cheap suites are green.
- **Dependency scanning is Trivy plus two ecosystem-native tools.** Trivy reads all three lockfiles in one pass and needs no NVD API key, which is what rules out the OWASP dependency-check Maven plugin — Trivy is the only practical way to get the Maven tree scanned on *every* run rather than nightly. It runs with `ignore-unfixed`, because a vulnerability with no released fix cannot be actioned by the pipeline and failing on it only trains people to ignore the job. `npm audit` is `--omit=dev`: a dev-only advisory cannot reach a user, and the frontend toolchain churns fast enough that gating on it would redden the build for reasons unrelated to the shipped bundle.
- `.github/dependabot.yml` is the other half — the CI job says a lockfile is vulnerable, Dependabot opens the PR that fixes it. Weekly and grouped, because five ecosystems on a daily cadence produces more PRs than this project has reviewers; security updates ignore both the schedule and the grouping.
- **The stack job must work without secrets.** PRs from forks never receive repository secrets, so the CI `.env` falls back to a placeholder Anthropic key and an empty Voyage key. The smoke test degrades gracefully. Triage reports as a NOTE rather than a failure, so the job still asserts the other 11 things and exits 0. Do not make triage a hard assertion there or every fork PR goes red.
- `JWT_SECRET` is generated with `openssl rand -base64 64` and `POSTGRES_PASSWORD` with `rand -hex 24`. The hex is deliberate: the password is interpolated into the RAG service's `DATABASE_URL`, so a base64 `/` or `+` would corrupt the connection string.
- Push triggers on every branch because all work here happens on unmerged feature branches; a PR therefore runs both the push and PR workflows. Accepted tradeoff. Restricting push to `main` would mean no CI at all until branches start merging.
- `scripts/smoke-test.sh` now also asserts **CORS** and **what URL is baked into the SPA bundle**. Both are silent-failure classes that no unit suite can reach: a wrong `CORS_ALLOWED_ORIGINS` leaves every service healthy and every test green while the app is unusable in a browser, and `VITE_API_BASE_URL` is inlined by Vite at build time, so building the image with a compose service name ships a bundle whose API calls the browser cannot resolve.
- The `rag-service` image is a **multi-stage build**: `build-essential` and `uv` stay in the build stage. That took it from 1.76GB to 548MB, which matters because CI rebuilds it on every run. Its healthcheck uses the venv interpreter rather than curl, so the runtime stage installs no apt packages at all.

**Failure modes that only a running stack exposes:**

- **The SPA could never log in.** `authResponseSchema` required an `accessTokenExpiresIn` number the backend has never sent, it returns `tokenType` and `expiresAt`. Zod's `parse()` threw on every successful login and registration: the API call succeeded, the user saw "Something went wrong", and the account was silently created. All 67 frontend tests passed because the MSW fixtures returned the *schema's* shape rather than the backend's. The field was never read anywhere. The smoke test asserts the live response carries every field the schema requires, because that is the only place the two sides meet.
- **Plaintext passwords in the logs.** A Java record's generated `toString()` prints every component, and Spring DEBUG-logs the deserialised request body, which the `dev` profile enables and docker-compose defaults to. Every login and registration wrote the user's password to the container log. `LoginRequest` and `RegisterRequest` now override `toString()`; `CredentialRedactionTest` pins it.
- **The backend would not start in production at all.** `requiresChannel().requiresSecure()` compiles fine and throws `NoClassDefFoundError: ChannelDecisionManager` at context startup — Spring Security 7 removed it. Every test passed, because the branch was gated on "not the dev or test profile" and the suite only ever runs those two. The first thing to execute that line was a production container, which then crash-looped, and compose gates the RAG service and the frontend on the backend, so the whole stack came up as one healthy Postgres. The lesson generalises past this bug: **a configuration switch that no test can turn on is untested**, which is why the replacement is a property rather than a profile check.
- **Security headers that were configured but never sent.** Every header in `nginx.conf` was absent from every actual response, because `add_header` inheritance is cancelled by any `add_header` in the matching `location` — and both real locations set `Cache-Control`. Nothing short of looking at a live response finds this; the config reads correctly.
- **`@PreAuthorize` denials returned 500.** Method security throws inside the controller invocation, so it reaches `GlobalExceptionHandler` rather than the security filter chain, and fell through to the generic handler. Nothing had used method security before `UserController`. Now 403 `forbidden`.
- **Provider failures returned 500 with a stack trace.** A missing `VOYAGE_API_KEY` crashed the request instead of reporting an upstream fault. `UpstreamError` (in `rag-service/.../errors.py`) now wraps outbound provider calls in both adapters and maps to 502 `upstream_error`; `TriageError` subclasses it, so one handler covers an unusable Claude reply and a call that never landed.

**Re-triage endpoint:**

- `POST /tickets/{id}/triage` re-runs triage on demand. Added because provider rate limits make a failed automatic triage routine. Voyage's free tier allows **3 requests a minute**, so a burst of tickets leaves some untriaged with nothing actually wrong.
- **Synchronous, and it reports failure**, the opposite of the automatic path. The post-commit listener degrades silently on purpose; this one is a button somebody pressed, so silence would be useless. Failures surface as 502 `triage_failed` or 503 `triage_disabled` (the latter distinguishes "never configured" from "call didn't work").
- **Staff only, and a non-staff caller gets 404, not 403**. Matching `TicketService.assign`, so ticket ids stay unguessable.
- It **overwrites** an existing result and **appends a second TRIAGED row** with `retriggered: true` rather than editing the first. The audit trail should show that a human asked for another opinion. `TicketTriageWriter.applyTriage` takes a `force` flag for this; the automatic path stays first-write-wins so two racing listeners cannot produce two rows.

**Error envelope:**

- All domain exceptions extend `com.ticketmind.backend.common.exception.ApiException` (status + stable machine code). `GlobalExceptionHandler` translates them to `ApiError { status, code, fieldErrors? }`. Exception messages are **never** returned to the client. Only the code. The generic `Exception` handler logs the real cause and returns `internal_error` with HTTP 500, so internal class names / messages don't leak.
- `NoResourceFoundException` has an explicit handler returning 404 `not_found`; without it, Spring Boot 4 routes it through the generic handler and returns 500.
- `ApiException` is shared by every domain, not just auth, so ticket and user errors use the same envelope.

**Data protection (V3):**

- **Three database credentials, not one.** `POSTGRES_USER` owns the schema and is used by Flyway and nothing else. The backend runs as `ticketmind_app` and the RAG service as `ticketmind_rag`, both created by V3, both `NOSUPERUSER NOBYPASSRLS`, neither owning a table. That last part is load-bearing: a table's owner is exempt from its own policies unless the table is FORCEd, and a superuser is exempt unconditionally, so an app connecting as the owner would make every policy below decorative.
- `ticketmind_rag` holds `CREATE` on the schema (it creates and owns `kb_documents` itself, which is why that table is deliberately not in Flyway) and no privilege at all on `tickets`, `users`, or `refresh_tokens`. This is the container that hands text to a third-party model, so it is the one whose reach matters most. `scripts/smoke-test.sh` asserts the denial against the live stack.
- **`RlsConfig` wraps the `DataSource`** so every connection issues `SET ROLE`, then `set_config('app.user_id'/'app.user_role')` from the authenticated principal, and resets both on return. The reset is not optional: Hikari hands the same physical connection to the next request, and a leftover `app.user_role=ADMIN` would hand that request an administrator's view. `set_config()` rather than `SET` because `SET` takes no bind parameters and this carries a value derived from a token.
- The `BeanPostProcessor` that installs it is `static` and binds its own config through `Binder`. A `BeanPostProcessor` is built before the regular bean graph, so injecting `RlsProperties` would force the properties infrastructure to initialise early and Spring logs about it forever after.
- A `FlywayMigrationStrategy` suspends the role switch for the duration of the migration. V3 creates the very role every other connection switches into; migrating through it would be circular on a fresh database.
- **Work with no principal runs as `SYSTEM`**, and the policies grant `SYSTEM` broad access. That covers login, refresh, the async triage write-back, and the admin bootstrap — paths that are pre-authentication or off-request by nature, since the login lookup has to read a user row before it knows who is asking. It is a documented hole, not an accident.
- `JwtAuthenticationFilter` therefore **skips** `/auth/register|login|refresh|logout`. Without that, a client sending a stale `Authorization` header alongside a registration would run the INSERT as that user rather than as SYSTEM, and the `users_insert` policy would reject it. `/auth/me` still authenticates.
- **DELETE is spelled out separately in every policy** rather than folded into `FOR ALL`. A `FOR ALL` policy has no `WITH CHECK` path for DELETE, so `USING` alone decides it: `FOR ALL USING (app_is_staff())` would hand every agent the ability to delete rows the API never lets anyone delete.
- There is no self-UPDATE policy on `users`. Role, lockout counters and the password hash all live on that row, so the only writers are SYSTEM and ADMIN — a submitter cannot promote themselves even with a working SQL injection.
- **Column encryption is AES-256-GCM** via `EncryptedStringConverter`, over `tickets.title`, `tickets.description`, `tickets.suggested_resolution`, and `ticket_history.payload` (which carries comment bodies). GCM rather than CBC because it authenticates: a row edited directly in Postgres fails to decrypt instead of silently returning attacker-chosen plaintext. The IV is random per value, so two tickets reporting the same problem are not visibly identical in a dump.
- Stored form is `v1:<b64 iv>:<b64 ciphertext||tag>`. The version prefix makes rotation possible without a big-bang migration, and values *without* the prefix are read through untouched — the read half of encrypt-on-write, so rows predating the converter stay readable and are re-encrypted on next save. Writes always encrypt.
- Those columns moved to `TEXT` in V3 because the envelope is longer than the plaintext. `ticket_history.payload` left JSONB behind at the same time; nothing ever queried it with JSONB operators, since the audit log is only read whole, by ticket.
- **The RAG service encrypts `kb_documents` with the same key and the same envelope** (`rag-service/.../crypto.py`). The knowledge base is a mirror of resolved tickets, so leaving it in the clear would have made the backend's encryption decorative. Embeddings are computed over plaintext before encryption — ciphertext has no semantics to embed — which leaves the vector as the one column that still says something about the content. Searchable encryption is a different problem than this one.
- `APP_ENCRYPTION_KEY` has **no default**, exactly like `JWT_SECRET`: a fallback key is worse than a failed startup, because it encrypts production data under a value that is in the source tree. Back it up somewhere other than the database.
- Testcontainers connects as the superuser it provisions, which would be exempt from RLS. The `SET ROLE` on checkout is what makes the suite exercise the real policies, and `RowLevelSecurityIntegrationTest` asserts them through raw SQL rather than through `TicketService` — a test that went through the Java checks again would pass just as happily with every policy dropped.
- `SchemaMigrationTest.applicationRoleCannotSeeFlywayBookkeeping` is the cheap canary: `information_schema` only lists what the current role has a privilege on, and the app role is granted nothing on `flyway_schema_history`. If the role switch ever silently stops happening, that table reappears.

**Security wiring:**

- `SecurityConfig` is stateless: no sessions, CSRF disabled (we use Bearer tokens for data endpoints), form login / HTTP basic / Spring's logout all disabled. HSTS 1y `includeSubDomains`, frame options DENY, referrer policy NO_REFERRER, a `default-src 'none'` CSP, and a Permissions-Policy that denies every device API.
- **`HttpsEnforcementFilter` refuses any request that did not arrive over TLS**, gated on `app.security.require-https` (default true; dev and test set it false). `request.isSecure()` reflects `X-Forwarded-Proto` because `server.forward-headers-strategy: framework` installs Spring's `ForwardedHeaderFilter` ahead of it. TLS is assumed to terminate upstream rather than in these containers.
- It **refuses rather than redirects**, which is the right shape for an API: a 301 on a POST loses the body, browsers will not re-send it, and a cross-origin redirect drops the `Authorization` header, so a redirect would turn one clear misconfiguration into several confusing partial failures. Browser traffic is redirected at the edge in nginx, where the request is a GET for a document.
- **Actuator is exempt**, and that exemption is load-bearing: the container healthcheck probes `http://localhost:8080/actuator/health` over loopback, and compose gates both the RAG service and the frontend on the backend reporting healthy.
- The gate is a **property, not a profile check**. Keying it off the active profile put it beyond the reach of the whole suite — dev and test both disable it, and those are the only profiles the tests run under. `HttpsEnforcementIntegrationTest` forces the property on and boots the full context, which is most of the value: the failure this replaced was a context-startup error, not a behavioural one.
- Filters use `getRequestURI()` minus the context path, never `getServletPath()`. The latter is populated by the container's servlet mapping and comes back empty under MockMvc, which silently disabled the actuator exemption inside the very test written to prove it worked.
- The API's CSP is `default-src 'none'` because it returns JSON and nothing else. It costs nothing and neuters a reflected payload that ever manages to get rendered as a page.
- Public matchers: `/actuator/**`, `/error`, POST `/auth/register|login|refresh|logout`. Everything else is `authenticated()`.
- `JwtAuthenticationFilter` reads `Authorization: Bearer <token>` and seats a `JwtPrincipal(id, email, role)` as the `Authentication.principal`. Use `@AuthenticationPrincipal JwtPrincipal principal` in controllers.
- `@EnableMethodSecurity` is on; method-level `@PreAuthorize("hasRole('AGENT')")` etc. works.

**Frontend:**

- Access token lives in module memory only (`src/api/tokenStore.ts`), never in localStorage/sessionStorage. XSS should not be able to harvest a persistent credential. Refresh survives page reload because the backend's HttpOnly `tm_refresh` cookie is still valid; the app performs a silent `/auth/refresh` on mount (`AuthProvider`).
- `apiFetch` (`src/api/client.ts`) attaches `Authorization: Bearer …` when a token exists, transparently refreshes-and-retries **once** on 401, and clears the token when refresh itself fails. Concurrent refreshes are serialised into a single in-flight promise. Many stale requests firing at once would otherwise walk into the backend's family-revocation trap.
- Auth endpoints use `skipAuth: true` so `/auth/login`/`/auth/register`/`/auth/refresh` never recurse through the 401-refresh path.
- API errors are decoded through the backend's `{ status, code, fieldErrors? }` envelope into an `ApiError` class; user-facing copy is centralised in `src/lib/errorMessages.ts` (keyed by `code`, never showing raw messages). `Retry-After` seconds land on `ApiError.retryAfterSeconds` for rate-limit UI.
- Form validation mirrors the backend rules with Zod (via React Hook Form + `@hookform/resolvers/zod`). The registration form's 12-char + letter + digit check exists so users see feedback before the network round-trip; the backend still enforces it.
- The Prod SPA is served from Vite's `dist/`. In dev, the Vite server runs on `:3000` (matching `CORS_ALLOWED_ORIGINS` in `.env.example`); the Spring backend runs on `:8080`.

- The detail page polls `['ticket', id]` every 3s while `triagedAt` is null, and stops after 2 minutes (`TRIAGE_POLL_WINDOW_MS`) so a ticket the RAG service never triaged doesn't poll forever. Same window drives the "Analysing this ticket…" hint. Without this the async triage would only appear on a manual refresh.

**Frontend test conventions:**

- `vitest.config.ts` is separate from `vite.config.ts` because Vitest 3 still bundles a Vite 5 API. The React plugin type from Vite 8 conflicts, so `vitest.config.ts` casts `react()` to `never`. This is intentional; both configs work at runtime.
- MSW handlers must be registered per-test with `server.use(...)`. Global setup uses `onUnhandledRequest: 'error'`. Any un-mocked request fails loudly. `tokenStore.clear()` runs in `afterEach`.
- `renderWithProviders` (`src/test/renderApp.tsx`) wraps in `MemoryRouter` + `QueryClientProvider` + `AuthProvider`. `AuthProvider` fires a silent `/auth/refresh` on mount, so tests must mock that endpoint (return 401 for anonymous state).
- `.env.test` sets `VITE_API_BASE_URL=http://api.test`, MSW handlers key off that same base.

**RAG service:**

- The service owns its own `kb_documents` table, created with `IF NOT EXISTS` at startup, it does **not** read the backend's `ticket_embeddings`. Flyway lives with the Java service; a cross-service Flyway migration would couple the two deploys, and a schema mistake here would be able to corrupt ticket/auth data. The embedding dimension is templated into the DDL from `RAG_EMBEDDING_DIM` so a model swap is a config change plus a re-embed.
- Vectors are bound as `pgvector.Vector`, never as plain lists. A bare `list[float]` is sent as `float8[]`, which Postgres can only coerce when a target column supplies the type, so `INSERT` silently works while `embedding <=> %s` in the search query fails with "No operator matches the given name and argument types".
- `AnthropicChat` passes **no `temperature`**. Claude Opus 5 (and every Opus 4.7+ model) rejects sampling parameters with a 400; determinism is steered by the prompt instead.
- Auth is a single shared secret on `X-Internal-Key`, compared with `hmac.compare_digest`. Missing and wrong keys both return `401 unauthorized`. Inside the compose network, a foothold on another container shouldn't be able to enumerate whether a header is needed at all.
- `Embedder` and `ChatModel` are `Protocol`s with `Fake*` implementations, so the whole HTTP surface is testable without a Voyage/Anthropic key. `FakeEmbedder` hashes the input to a unit vector, which makes cosine ordering meaningful and deterministic.
- Long-lived objects (pool, embedder, chat, repo, triage service) live on `app.state`, wired in the lifespan, and dependency callables pull them off the `Request`. Tests pre-seed `app.state` before entering the lifespan, so the lifespan only builds what isn't already there, and only closes the pool it opened (`app.state._owns_pool`).
- The LLM contract is a bare JSON object. `_extract_json` pulls the first `{...}` block out of the reply because real replies occasionally wrap in prose or fences; anything unparseable raises `TriageError` → **502 `upstream_error`**, so the backend treats it as an upstream fault rather than a bad request. Citation scores come from the retrieval results, never from the model's own confidence claims.
- The Dockerfile execs `/app/.venv/bin/uvicorn` directly. `uv run` would re-resolve the environment at container start, which needs network access the container doesn't have.

## Test conventions

- **Integration tests use Testcontainers, not mocks.** The `TestcontainersConfiguration` spins up a real pgvector container; tests like `AuthFlowIntegrationTest` hit the real DB. Do **not** introduce `@MockBean` on repositories. The original CLAUDE.md guidance to mock the DB is superseded.
- Tests must annotate `@ActiveProfiles("test")`. The test profile sets a fixed `JWT_SECRET`, dials BCrypt strength to 4 (production minimum is 12), and uses a known schema.
- Do **not** put `@Transactional` on integration test classes that exercise services using `Propagation.REQUIRES_NEW` (e.g. failed-login bookkeeping, family revocation). Spring's test-class transaction rolls back the outer transaction, but REQUIRES_NEW inner transactions can't see uncommitted outer state. Tests fail with confusing "user not found" errors. Use explicit `@BeforeEach` cleanup via `repository.deleteAllInBatch()` instead.
- `ObjectMapper` is **not** an autowireable bean in `@SpringBootTest` slices here, instantiate `new ObjectMapper()` in tests.
- Run `./mvnw test` after **every** change. Don't push a branch with a red bar.
- RAG service: same rule with `uv run pytest`. One pgvector container per session (`pg_container` is session-scoped); the `pool` fixture truncates `kb_documents` between tests rather than recreating the schema. `pythonpath = ["."]` in `pyproject.toml` is what makes `from tests.conftest import ...` resolve. Coverage is gated at 80%.

## Code style

- Java: explicit braces, descriptive identifiers, no needless comments. A comment exists only when the *why* is non-obvious. A hidden constraint, a subtle invariant, a workaround for a specific Spring/Hibernate quirk. Don't comment what the code says.
- Python: `from __future__ import annotations`, full type hints on public functions, module docstrings that explain the *why* of the module. Same comment rule as Java. `ruff format` is authoritative, don't hand-wrap lines it would rejoin.
- Don't introduce premature abstractions. Three similar lines is fine.
- Don't add backwards-compat shims, deprecated re-exports, or `// removed` markers when deleting code.
- Don't write `_unused` helpers, mock layers, or "ready for X" scaffolding unless the next step actually uses them.

## Git workflow

- One feature per branch; branch names `feature/<slice>`.
- Commit messages are imperative ("add", not "added"), with a body that explains *why* and any non-obvious decision. The auth commit on `feature/auth` is the reference for length / depth.
- Each PR is a single coherent slice. The auth branch is large because the slice is large, not because changes are batched.
- Don't `--no-verify`, don't force-push. If a hook fails, fix the underlying issue.
- `.githooks/pre-commit` blocks staged content shaped like a live credential (Anthropic, OpenAI, Voyage, GitHub, AWS, PEM blocks). It is versioned but `core.hooksPath` is local config, so each clone runs `git config core.hooksPath .githooks` once. The patterns require real key length, so `sk-ant-replace-me` and the `sk-ant-super-secret` fixture in `rag-service/tests/test_config.py` pass. If a rule fires on a fixture, shorten the fixture rather than loosening the rule.

## Out of scope

Kubernetes, Terraform, and Kafka are intentionally not part of this build. Keep infra additions limited to Docker Compose unless explicitly asked to expand scope.
