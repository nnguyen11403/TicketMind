# TicketMind

AI-assisted support ticket triage system. Submit a bug report or support ticket, and the system automatically categorizes it, assigns a priority, and surfaces a suggested resolution by retrieving relevant context from past tickets and documentation.

Built to mirror how real support/engineering teams handle ticket triage at scale. Combining a traditional CRUD backend with a retrieval-augmented generation (RAG) pipeline for intelligent automation.

## Features

- Submit, view, and manage support tickets through a React dashboard
- Automatic categorization and priority scoring on ticket creation via LLM
- RAG pipeline that retrieves similar historical tickets/docs and grounds the AI's suggested response in real prior cases
- Dashboard analytics: ticket volume over time, category breakdown, resolution trends
- Fully containerized local dev environment with one-command startup
- CI pipeline that runs the test suite and builds Docker images on every push

## Tech Stack

**Backend:** Java, Spring Boot, PostgreSQL
**AI / RAG:** LangChain, pgvector, OpenAI/Claude API
**Frontend:** React
**Infrastructure:** Docker, Docker Compose, GitHub Actions (CI/CD)
**Testing:** Spring Boot Test, Cucumber, Selenium
**Deployment:** [Render / Fly.io / AWS, fill in once deployed]

## Architecture

```
┌─────────────┐      ┌──────────────────┐      ┌─────────────────┐
│   React     │ ───► │  Spring Boot API │ ───► │   PostgreSQL     │
│  Frontend   │      │   (Ticket CRUD)  │      │  (tickets, users)│
└─────────────┘      └────────┬─────────┘      └──────────────────┘
                               │
                               ▼
                      ┌──────────────────┐      ┌──────────────────┐
                      │  RAG Service     │ ───► │   pgvector       │
                      │  (LangChain)     │      │ (embedded tickets│
                      │                  │ ◄─── │   & docs)        │
                      └────────┬─────────┘      └──────────────────┘
                               │
                               ▼
                      ┌──────────────────┐
                      │   LLM API        │
                      │ (categorize +    │
                      │  suggest fix)    │
                      └──────────────────┘
```

When a new ticket is submitted, the backend stores it in Postgres, then triggers the RAG service, which embeds the ticket text, retrieves the most similar past tickets/docs from pgvector, and passes that context to an LLM to generate a category, priority, and suggested resolution, which is written back to the ticket record.

## Getting Started

### Prerequisites
- Docker and Docker Compose
- An API key for OpenAI or Anthropic

### Run locally
```bash
git clone https://github.com/<your-username>/ticketmind.git
cd ticketmind
cp .env.example .env   # add ANTHROPIC_API_KEY and VOYAGE_API_KEY
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend API: http://localhost:8080
- RAG service: http://localhost:8000

Once the stack is up, `./scripts/smoke-test.sh` checks all three services and
runs a ticket through end to end.

Without a `VOYAGE_API_KEY` the stack still runs, tickets are created normally,
they just stay untriaged.

### Run tests
```bash
cd backend      && ./mvnw test    # JUnit + Testcontainers (Docker required)
cd frontend     && npm test       # Vitest + Testing Library + MSW
cd rag-service  && uv run pytest  # pytest + Testcontainers
```

## Project Status

In active development. Current milestones:
- [x] Ticket CRUD API + Postgres schema
- [x] React frontend (submission form, ticket list/detail)
- [x] RAG pipeline (embedding + retrieval + LLM categorization)
- [x] Docker Compose full stack
- [x] CI/CD pipeline
- [ ] Live deployment

## Why This Project

Built as a hands-on exploration of how AI-assisted automation fits into a traditional full-stack application. The kind of pattern increasingly common in production support and DevOps tooling, where LLMs augment rather than replace existing workflows.

## License

MIT
