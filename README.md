# BusLink

A production-quality BMTC digital ticketing and transport platform, built as a backend engineering portfolio project. See `CLAUDE.md` and `docs/ARCHITECTURE.md` for the full context and design rationale.

**Status:** Sprint 1 (infrastructure + entities) — no REST APIs, business logic, or authentication yet. See `docs/PROJECT_CONTEXT.md` for current progress.

## Tech Stack

Java 21, Spring Boot 4.1.0, Maven, PostgreSQL, Docker Compose, Spring Security, Lombok, springdoc-openapi (Swagger).

## Prerequisites

- Java 21
- Maven (or use the included `./mvnw` wrapper — no local Maven install needed)
- Docker and Docker Compose

## 1. Clone the repository

```bash
git clone https://github.com/ChaitanyaNadagouda/buslink.git
cd buslink
```

## 2. Configure environment variables

Copy the example env file and fill in real values:

```bash
cp infrastructure/.env.example infrastructure/.env
```

Edit `infrastructure/.env` and set `POSTGRES_USER`, `POSTGRES_PASSWORD`, `PGADMIN_DEFAULT_EMAIL`, `PGADMIN_DEFAULT_PASSWORD`, and `JWT_SECRET` (generate with `openssl rand -base64 32`). This file is gitignored and must never be committed.

## 3. Start PostgreSQL and pgAdmin

```bash
docker compose up -d
```

This starts:
- **PostgreSQL** on `localhost:5432` (database `buslink`, auto-created from `POSTGRES_DB`)
- **pgAdmin** on `localhost:5050` (log in with the `PGADMIN_DEFAULT_EMAIL`/`PASSWORD` from your `.env`)

## 4. Run the backend

The app reads `${POSTGRES_USER}`/`${POSTGRES_PASSWORD}`/`${POSTGRES_DB}`/`${JWT_SECRET}` from `application.properties`, which in turn expects them as real environment variables — `infrastructure/.env` is only read by Docker Compose, not by Spring Boot directly. Load it into your shell before running:

```bash
cd backend
set -a && source ../infrastructure/.env && set +a
./mvnw spring-boot:run
```

**If running from IntelliJ instead:** add `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, and `JWT_SECRET` as environment variables in the Run Configuration for `BusLinkApplication`, since IntelliJ won't pick up `infrastructure/.env` automatically either.

The app starts on `localhost:8080`. Schema is managed via `ddl-auto=update` (no Flyway yet — deferred to post-LLD).

## 5. Verify it's running

- **Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **OpenAPI spec:** http://localhost:8080/v3/api-docs

No login required — `SecurityConfig` currently permits all requests (real auth is a later sprint).

## Project Structure

```
buslink/
├── backend/          # Spring Boot application
│   └── src/main/java/com/buslink/
│       ├── controller/   service/   repository/
│       ├── entity/       enums/     dto/
│       ├── security/     config/    exception/
│       ├── mapper/       validator/ util/
│       └── event/        scheduler/ cache/
├── docs/             # Architecture, sprint plans, project context
├── infrastructure/   # Docker Compose env config
└── docker-compose.yml
```

See `docs/ARCHITECTURE.md` for the reasoning behind this layout.
