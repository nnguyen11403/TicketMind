# TicketMind

AI-assisted support ticket triage. Users submit tickets through a React dashboard; a Python RAG service embeds each one, retrieves the most similar resolved tickets from pgvector, and asks Claude for a category, a priority, and a suggested resolution, which the Spring Boot backend writes back to the ticket.

Built to mirror how a real support tool would have to work rather than how a demo can get away with working: triage is asynchronous and best-effort, so a provider outage degrades the product instead of breaking ticket submission, and every design decision that isn't obvious from the code is written down in **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## What it does

- **Ticket lifecycle** — submit, edit, comment, assign, and move through `OPEN → IN_PROGRESS → WAITING → RESOLVED → CLOSED`, with an append-only history log rendered as a timeline.
- **Automatic triage** — on creation, a RAG pipeline assigns a category and one of `LOW/MEDIUM/HIGH/CRITICAL`, and drafts a resolution grounded in retrieved prior cases. The submit response returns before triage lands; the detail page polls until it arrives, then stops.
- **Staff-initiated re-triage** — a button that re-runs triage synchronously and reports failure, unlike the automatic path, which degrades silently on purpose. Added because free-tier embedding rate limits make a failed automatic triage routine.
- **Role-based access** — submitters see only their own tickets; agents and admins see the queue and can assign and change status. Admins can change roles through `PATCH /users/{id}/role`.
- **Queue sorting** — filter by status, sort by age, recent activity, or urgency. Priority sorts by a derived rank, because the column stores enum names and would otherwise order `CRITICAL, HIGH, LOW, MEDIUM` alphabetically.

## Tech stack

| Layer | Stack |
|---|---|
| Frontend | React 19, TypeScript 6, Vite 8, React Router v7, TanStack Query v5, React Hook Form + Zod, Tailwind v4 |
| Backend | Java 21, Spring Boot 4.1, Spring Security, JPA/Hibernate, Flyway, JJWT, Bucket4j |
| RAG service | Python 3.13, FastAPI, uv, psycopg 3 (async), LangChain + Claude, Voyage embeddings |
| Data | PostgreSQL 16 + pgvector (1024-dim, HNSW cosine index) |
| Infra | Docker Compose, nginx, GitHub Actions |

## Architecture

```
┌─────────────┐      ┌──────────────────┐      ┌──────────────────┐
│   React     │ ───► │  Spring Boot API │ ───► │   PostgreSQL     │
│  (nginx)    │      │   (Ticket CRUD)  │      │  (tickets, users)│
└─────────────┘      └────────┬─────────┘      └──────────────────┘
                              │ async, after commit
                              ▼
                     ┌──────────────────┐      ┌──────────────────┐
                     │   RAG Service    │ ───► │    pgvector      │
                     │    (FastAPI)     │ ◄─── │  (kb_documents)  │
                     └────────┬─────────┘      └──────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  Claude + Voyage │
                     └──────────────────┘
```

`TicketService` publishes an event that a listener handles `AFTER_COMMIT` on a bounded executor, so `POST /tickets` answers `201` with `category` and `priority` still null and the client sees them appear on a later fetch. Resolved tickets are mirrored back into the knowledge base keyed by ticket UUID, which makes today's resolutions retrieval context for tomorrow's triage.

## Security

Most of the engineering here went into the parts that don't demo:

- **Row-level security** — Postgres policies on tickets, history, users, and refresh tokens. Every connection switches into an unprivileged role and carries the caller's identity in session variables, so the isolation holds even if a query forgets to check.
- **Encryption at rest** — AES-256-GCM on ticket titles, bodies, suggested resolutions, and audit payloads, with a versioned envelope shared byte-for-byte between the Java and Python services so either can read what the other wrote.
- **Least-privilege database roles** — three credentials. Flyway owns the schema; the backend and the RAG service each get their own role. The service that talks to a third-party model cannot read `tickets` or `users`.
- **Auth** — stateless JWT with refresh-token rotation. Reusing an already-rotated token triggers family-wide revocation, since that signals theft. Only SHA-256 hashes of refresh tokens are stored. BCrypt cost 12, account lockout, per-IP rate limiting, and a decoy hash comparison on unknown emails to flatten the user-enumeration timing channel.
- **Transport and headers** — HSTS, CSP, and a Permissions-Policy on both tiers; non-TLS requests are refused outside local profiles.
- **Supply chain** — Trivy, `npm audit`, and `pip-audit` on every CI run, plus Dependabot across five ecosystems.

## Testing

265 tests across three languages, plus a cross-service smoke test.

```bash
cd backend      && ./mvnw test    # 140 — JUnit + Testcontainers
cd frontend     && npm test       # 71  — Vitest + Testing Library + MSW
cd rag-service  && uv run pytest  # 54  — pytest + Testcontainers (88% coverage)
```

Integration tests run against a real pgvector container rather than mocked repositories. That's a deliberate call: mocks agree with whatever you tell them, and the interesting bugs here were all disagreements between two real systems.

`scripts/smoke-test.sh` makes 40 assertions against a running stack, and exists because several defects were structurally unreachable from any unit suite — an HTTP/2 negotiation failure that only appeared over a real socket, a response-schema mismatch that broke login in the browser while all 67 frontend tests passed, and security headers that were present in the nginx config and absent from every response.

## Running it

**Prerequisites:** Docker and Docker Compose. An `ANTHROPIC_API_KEY` and `VOYAGE_API_KEY` if you want triage to actually run.

```bash
git clone git@github.com:nnguyen11403/TicketMind.git
cd TicketMind
cp .env.example .env      # then fill in the secrets it describes
docker compose up --build
```

- Frontend → http://localhost:3000
- Backend → http://localhost:8080
- RAG service → http://localhost:8000

`.env.example` documents every variable. Four have no safe default and the stack will not start without them: `JWT_SECRET`, `APP_ENCRYPTION_KEY`, `APP_DB_PASSWORD`, and `RAG_DB_PASSWORD`. That's intentional — a fallback secret checked into a repo is worse than a failed startup.

Without provider keys the stack still runs end to end; tickets are created normally and stay untriaged, which is the same graceful-degradation path a provider outage takes.

```bash
# Cross-service checks against the running stack
set -a; . ./.env; set +a; ./scripts/smoke-test.sh
```

To get the first staff account, register through the UI, set `BOOTSTRAP_ADMIN_EMAIL` to that address, and restart. It promotes only while no admin exists, so it can't be used to silently re-grant privileges later.

## Status

| Area | State |
|---|---|
| Auth, ticket CRUD, history audit | done |
| React frontend | done |
| RAG service and backend wiring | done |
| Docker Compose, CI | done |
| Security hardening (RLS, encryption, least-privilege roles) | done |
| Live deployment | not started |

**Known limits, deliberately.** Rate-limit buckets are in-memory, so this runs correctly as a single instance and would need Redis-backed buckets behind a load balancer. Postgres runs in Compose on a local volume with no backup or replication. The triage queue is in-process and bounded, and drops work on restart rather than delaying the user's request. Kubernetes, Terraform, and Kafka are out of scope on purpose.

## Why this project

An honest test of how a retrieval-augmented feature sits inside an ordinary CRUD application — where the LLM is one unreliable dependency among several rather than the center of the system. Most of the difficulty turned out to be exactly there: deciding what happens when the model is slow, wrong, rate-limited, or unavailable, and making sure none of those break the thing users actually came to do.

## License

MIT
