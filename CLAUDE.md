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
| 4. Auth (JWT + BCrypt + refresh rotation) | `feature/auth` | **pushed, 58/58 tests green** |
| 5. Ticket CRUD API | `feature/ticket-crud` | **in progress, uncommitted** |
| 6. React frontend bootstrap | — | pending |
| 7. Ticket UI | — | pending |
| 8. Python RAG service | — | pending |
| 9. Backend ↔ RAG wiring | — | pending |
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
```

The frontend and RAG service haven't been built yet — no commands for them.

## Tech stack (as actually built)

- **Java 21 LTS**, Spring Boot **4.1.0**, Maven wrapper
- **PostgreSQL** via Testcontainers using `pgvector/pgvector:pg16` (the vector extension is in V2, so any test container must be the pgvector image, not stock postgres)
- Spring Security stateless JWT (HS256) via **JJWT 0.12.6**
- **BCrypt** via `DelegatingPasswordEncoder` — prefix-prefixed hashes (`{bcrypt}$2a$...`) so the algorithm can be upgraded in place without a migration
- **Bucket4j 8.10.1** for per-IP rate limiting
- **Flyway** with `ddl-auto=validate` (entities must match V1/V2 exactly)
- **JPA Auditing** for `created_at` / `updated_at`
- Frontend (planned): Vite + React + TypeScript
- RAG service (planned): Python + FastAPI + LangChain + Anthropic Claude

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

## Test conventions

- **Integration tests use Testcontainers, not mocks.** The `TestcontainersConfiguration` spins up a real pgvector container; tests like `AuthFlowIntegrationTest` hit the real DB. Do **not** introduce `@MockBean` on repositories — the original CLAUDE.md guidance to mock the DB is superseded.
- Tests must annotate `@ActiveProfiles("test")`. The test profile sets a fixed `JWT_SECRET`, dials BCrypt strength to 4 (production minimum is 12), and uses a known schema.
- Do **not** put `@Transactional` on integration test classes that exercise services using `Propagation.REQUIRES_NEW` (e.g. failed-login bookkeeping, family revocation). Spring's test-class transaction rolls back the outer transaction, but REQUIRES_NEW inner transactions can't see uncommitted outer state — tests fail with confusing "user not found" errors. Use explicit `@BeforeEach` cleanup via `repository.deleteAllInBatch()` instead.
- `ObjectMapper` is **not** an autowireable bean in `@SpringBootTest` slices here — instantiate `new ObjectMapper()` in tests.
- Run `./mvnw test` after **every** change. Don't push a branch with a red bar.

## Code style

- Java: explicit braces, descriptive identifiers, no needless comments. A comment exists only when the *why* is non-obvious — a hidden constraint, a subtle invariant, a workaround for a specific Spring/Hibernate quirk. Don't comment what the code says.
- Don't introduce premature abstractions. Three similar lines is fine.
- Don't add backwards-compat shims, deprecated re-exports, or `// removed` markers when deleting code.
- Don't write `_unused` helpers, mock layers, or "ready for X" scaffolding unless the next step actually uses them.

## Git workflow

- One feature per branch; branch names `feature/<slice>`.
- Commit messages are imperative ("add", not "added"), with a body that explains *why* and any non-obvious decision. The auth commit on `feature/auth` is the reference for length / depth.
- Each PR is a single coherent slice — the auth branch is large because the slice is large, not because changes are batched.
- Don't `--no-verify`, don't force-push. If a hook fails, fix the underlying issue.

## Where step 5 (ticket CRUD) left off

`feature/ticket-crud` is checked out with uncommitted work. **Built so far:**

- `ticket/` package: `Ticket`, `TicketHistory`, `TicketStatus`, `TicketPriority`, `TicketHistoryEventType`, `TicketRepository`, `TicketHistoryRepository`.
- All ticket DTOs under `ticket/dto/`: `CreateTicketRequest`, `UpdateTicketRequest`, `AssignTicketRequest`, `ChangeStatusRequest`, `AddCommentRequest`, `TicketResponse`, `TicketSummaryResponse`, `TicketHistoryResponse`, `UserSummary`, `PagedResponse`.
- `TicketService` — full authorisation logic. Role rules:
  - USERs only see their own tickets; staff (AGENT/ADMIN) see all.
  - "Not authorised" surfaces as **404, not 403** to prevent ID enumeration (`assertCanRead`).
  - Submitters can edit body only while ticket is OPEN; can self-close OPEN tickets; cannot change any other status.
  - Only staff can assign. Assignee must be AGENT or ADMIN (active) or `null` to unassign — enforced by `AgentRequiredException`.
  - Every mutation writes a `TicketHistory` row (CREATED, UPDATED, STATUS_CHANGED, RESOLVED, REOPENED, ASSIGNED, COMMENT, TRIAGED). The payload is JSON-serialised via `ObjectMapper`.
- New exceptions: `TicketNotFoundException` (404), `InvalidTicketStateException` (409), `AgentRequiredException` (400).
- `AuthException` was renamed to `ApiException`; all 6 existing exception subclasses and `GlobalExceptionHandler` were migrated.

**Still TODO on step 5:**

1. `TicketController` REST endpoints:
   - `POST   /tickets`              — submit (any authed user)
   - `GET    /tickets/{id}`         — read one (authz in service)
   - `GET    /tickets?status=&page=&size=` — list, paged
   - `PATCH  /tickets/{id}`         — update body
   - `POST   /tickets/{id}/status`  — change status
   - `POST   /tickets/{id}/assign`  — staff-only
   - `POST   /tickets/{id}/comments`
   - `GET    /tickets/{id}/history`
2. Make `Ticket.applyTriage(...)` callable from the eventual RAG callback path (step 9) — leave a public method on the service that's role-gated to AGENT/ADMIN only, since the RAG webhook will authenticate as a service user.
3. Tests under `src/test/java/com/ticketmind/backend/ticket/`:
   - `TicketServiceTest` — authorisation matrix (USER vs AGENT vs ADMIN; own vs other ticket; status-transition rules).
   - `TicketControllerIntegrationTest` — full HTTP flow including 404-on-unauthz, paging, history, comment.
4. Run `./mvnw test`, confirm green, then commit + push `feature/ticket-crud`.

**No new Flyway migration is needed** — the `tickets` and `ticket_history` tables are already in V1 and match the entities as written.

## Out of scope

Kubernetes, Terraform, and Kafka are intentionally not part of this build. Keep infra additions limited to Docker Compose unless explicitly asked to expand scope.
