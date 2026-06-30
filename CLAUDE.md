# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

TicketMind is an AI-assisted support ticket triage system. Users submit tickets through a React frontend; a Spring Boot backend persists them to PostgreSQL; a RAG pipeline (LangChain + pgvector) retrieves similar past tickets/docs and calls an LLM to auto-categorize, prioritize, and suggest a resolution.

See `README.md` for the full architecture diagram and feature list.

## Tech Stack

- **Backend:** Java 17+, Spring Boot, PostgreSQL
- **AI/RAG:** LangChain, pgvector, OpenAI or Anthropic API
- **Frontend:** React
- **Infra:** Docker, Docker Compose, GitHub Actions
- **Testing:** Spring Boot Test, Cucumber, Selenium

## Commands

```bash
# Run full stack locally
docker-compose up --build

# Backend tests
./mvnw test

# Backend tests, single class
./mvnw test -Dtest=TicketControllerTest

# Frontend dev server
cd frontend && npm start

# Frontend tests
cd frontend && npm test

# Lint backend
./mvnw checkstyle:check

# Lint frontend
cd frontend && npm run lint
```

## Code Style

- **Java:** explicit braces on all control structures, no unnecessary comments, descriptive variable names matching existing naming conventions in the file being edited. Favor explicit, readable logic over compact/clever one-liners.
- **React:** functional components with hooks, no class components.
- **Tests:** white-box tests for service-layer logic; mock the database layer rather than hitting a real connection in tests (Spring Boot tests must use `@MockBean` for repositories — never let a test create a real DB connection).
- **Commits:** small, scoped commits with imperative-mood messages (e.g. "Add ticket priority field", not "Added" or "Adding").

## Architecture Notes

- `backend/` — Spring Boot service: ticket CRUD, auth, REST API
- `rag-service/` — handles embedding tickets/docs into pgvector and orchestrating LLM calls for categorization
- `frontend/` — React app: ticket submission, list/detail views, dashboard
- New tickets trigger an async call from `backend/` to `rag-service/` after the initial DB write; the suggested category/priority/response is written back to the ticket record once the RAG service responds — don't block the ticket creation API response on the LLM call.

## Working Conventions

- When adding a new API endpoint, add a corresponding integration test in the same PR.
- When touching the RAG pipeline, do not commit real API keys — use `.env` (already gitignored) and reference `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` via environment variables only.
- Keep `docker-compose.yml` in sync with any new service or environment variable added to `backend/` or `rag-service/`.
- Update the milestone checklist in `README.md` when a tracked feature is completed.
- Prefer Cucumber/Selenium step definitions consistent with existing step definition files rather than creating duplicate/overlapping steps.

## Out of Scope (for now)

Kubernetes, Terraform, and Kafka are intentionally not part of this build — keep infra additions limited to Docker Compose unless explicitly asked to expand scope.