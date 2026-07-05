# ARCHITECTURE.md

This document explains the overall architecture of BusLink — the high-level design and the reasoning behind it. It intentionally avoids implementation details (those belong in code, `docs/`, and inline comments); this is the "why" and "shape" of the system, not the "how."

---

## Project Vision

BusLink is a production-quality BMTC (Bangalore transit) digital ticketing and transport platform, built as a flagship backend engineering portfolio project. The primary goal is to learn enterprise backend engineering by building a system the way a real engineering team would: layered architecture, containerized infrastructure, migration-managed schema, secured APIs, and documentation that tracks decisions as they're made — not a toy CRUD app.

The domain (BMTC ticketing) was chosen because it naturally requires the kinds of systems a backend engineer is expected to design in industry: authentication, payments, real-time validation (QR scanning at boarding), and reporting — while still being scoped enough for one person to build end-to-end.

---

## High-Level Architecture

BusLink is currently a **backend-only, monolithic, layered application** running on containerized infrastructure:

- **Application layer** — a single Spring Boot service (monolith, not microservices) exposing REST APIs.
- **Data layer** — PostgreSQL, schema-versioned via Flyway.
- **Infrastructure layer** — Docker Compose orchestrating the app and its dependencies (Postgres, pgAdmin) as isolated, reproducible containers.

A monolith is the deliberate starting point, not an oversight: microservices solve organizational and independent-scaling problems that don't exist yet for a single-developer project in its first sprints. Splitting services prematurely would add network boundaries, distributed transactions, and deployment complexity without a corresponding benefit. The layered architecture inside the monolith (see below) keeps the codebase modular enough to extract services later *if* a real need for that emerges (e.g., QR validation needing independent scaling under load).

---

## Technology Choices

| Concern | Choice |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Build tool | Maven |
| Database | PostgreSQL |
| Schema migrations | Flyway |
| Auth | Spring Security + JWT |
| Object mapping | MapStruct |
| Boilerplate reduction | Lombok |
| API docs | Swagger (springdoc-openapi 3.0.3) |
| Testing | JUnit, Mockito |
| Local infra | Docker, Docker Compose |
| Frontend (future) | React |

### Note on Spring Boot version (2026-07-05)

Sprint 1 was originally scoped for Spring Boot 3.x. By the time the project was generated, Initializr's default had moved to **Spring Boot 4.1.0** — 3.x had become the trailing legacy line (latest patch `3.5.16`). We chose to generate on 4.1.0 rather than pin to legacy 3.x, since this project favors current industry practice over matching an already-stale plan.

This carries real shape changes worth remembering when reading Boot-3-era tutorials/docs:

- `spring-boot-starter-web` → **`spring-boot-starter-webmvc`** (Boot 4 splits the web starter explicitly between MVC and WebFlux).
- The single `spring-boot-starter-test` is now **split per starter** (`spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, etc.) rather than one bundle pulling in everything.
- `springdoc-openapi` is pinned to **3.0.3** (the 2.x line targets Spring Framework 6 / Boot 3; 3.x targets Spring Framework 7 / Boot 4). It has never been an Initializr-catalog dependency in either line and is added to `pom.xml` by hand.

Going forward: if a tech decision changes mid-implementation because a better/newer option surfaces, update this file and the active sprint file immediately rather than waiting for sprint close — these docs should never lag behind what's actually running.

### Reasons for choosing PostgreSQL

BusLink's domain is relational at its core — riders, routes, tickets, payments, and bus stops all have well-defined relationships and need transactional integrity (a payment and a ticket issuance must succeed or fail together). PostgreSQL is chosen over NoSQL alternatives because:

- **ACID transactions** are a hard requirement for anything touching payments or ticket state — losing or double-issuing a ticket is unacceptable.
- **Mature relational modeling** (foreign keys, constraints, joins) fits a domain with clear entity relationships better than a document store would.
- **Industry ubiquity** — Postgres is the default relational database in most production Spring Boot stacks, so learning it transfers directly to real engineering roles.
- It pairs naturally with Flyway for version-controlled schema evolution, which a NoSQL store wouldn't need in the same way.

### Reasons for using Docker

- **Reproducibility** — the entire local dev environment (Postgres, pgAdmin, eventually the app itself) is described in one `docker-compose.yml`, so it behaves identically regardless of host machine.
- **Isolation from the host** — nothing is installed natively on Windows/WSL; Postgres versions can't drift from what's declared in code.
- **Production parity** — containers are how real backend services are deployed today (directly, or via Kubernetes). Learning Docker now is learning the deployment unit the industry actually uses.
- **Easy reset** — a broken local environment can be torn down and rebuilt from the compose file in seconds, rather than debugging a polluted native install.

Full rationale for the specific compose decisions (healthchecks, named volumes, image pinning, etc.) is in `docs/sprint-1-infrastructure.md`.

### Reasons for using Layered Architecture

The codebase is organized into `controller` → `service` → `repository` layers (plus supporting `model`, `dto`, `mapper`, `config`, `security`, `exception`, `validator`, `util`, `constants` packages), because:

- **Separation of concerns** — HTTP concerns (controllers), business rules (services), and persistence (repositories) change for different reasons and at different rates. Mixing them makes both testing and reasoning harder.
- **Testability** — services can be unit-tested with mocked repositories; controllers can be tested independently of business logic.
- **Industry standard** — this is the default architecture for Spring Boot applications and the one most engineers will recognize and expect when reading the code.
- **Low coupling / high cohesion** — each layer depends only on the abstraction below it (e.g., service depends on a repository interface, not a JDBC implementation), which is what makes the system maintainable as it grows.

Layered architecture is chosen over a more elaborate Clean/Hexagonal Architecture (ports & adapters) for now because the added indirection isn't justified yet at this project's size — but the dependency direction (outer layers depend on inner ones, not vice versa) is kept compatible with evolving toward that later if the domain logic grows complex enough to warrant it.

### Reasons for postponing the React frontend until the backend is stable

- **Contract-first development** — the backend's REST API is the contract every future client (React web app, a future mobile app, third-party integrations) depends on. Building it first, and getting it right (resources, status codes, error shapes, auth flow), avoids reshaping a contract that a frontend has already been built against.
- **Learning focus** — the stated goal of this project is backend engineering depth. Splitting attention across a frontend before the backend fundamentals (security, migrations, layered design, testing) are solid would dilute that.
- **Independent verification** — Swagger/OpenAPI and tools like Postman/curl can fully exercise and validate the backend without a UI, so a frontend isn't required to prove the backend works.
- **Reduced coupling during early instability** — the backend schema and API shape are expected to change frequently in early sprints (entities, auth, ticketing flow all still being designed). Building a frontend against a moving target creates rework in both places simultaneously.

---

## Current Architecture Diagram

```
                        ┌─────────────────────────┐
                        │        Developer         │
                        │   (browser / curl /       │
                        │    pgAdmin UI)            │
                        └────────────┬──────────────┘
                                     │
                     ┌───────────────┴───────────────┐
                     │                                │
                     ▼                                ▼
          ┌───────────────────┐            ┌────────────────────┐
          │  pgAdmin           │            │  Spring Boot App    │
          │  (container)       │            │  (not yet built)     │
          │  localhost:5050    │            │  planned: :8080      │
          └─────────┬──────────┘            └──────────┬──────────┘
                     │                                    │
                     │        buslink_network (Docker)     │
                     └───────────────┬────────────────────┘
                                      ▼
                          ┌────────────────────────┐
                          │  PostgreSQL             │
                          │  (container)            │
                          │  localhost:5432         │
                          │  volume: postgres_data  │
                          └────────────────────────┘
```

Everything above runs locally via Docker Compose. The Spring Boot application box is drawn as "not yet built" because Sprint 1's remaining objective is generating that project and connecting it to Postgres — see `PROJECT_CONTEXT.md` for current status.

---

## Future Architecture Roadmap

The following are anticipated additions, roughly in the order the domain requires them. Each will get its own architectural decision (problem → alternatives → chosen approach) when its sprint begins, not designed in detail upfront.

- **Authentication** — Spring Security + JWT for stateless API auth; likely role-based (rider vs. admin) from the start given the Admin Portal below.
- **Ticketing** — the core domain: routes, trips, fares, ticket issuance and lifecycle (issued → validated → expired).
- **Payments** — integration with a payment gateway; will need idempotency handling so a network retry can't double-charge or double-issue a ticket.
- **QR validation** — generating a verifiable QR per ticket and a fast validation endpoint for bus-side scanning; likely the first candidate for extraction into its own service if load/latency demands independent scaling.
- **Analytics** — reporting on ridership, revenue, and route usage; likely read-heavy and may eventually warrant a read replica or separate reporting store rather than querying the transactional database directly.
- **Notifications** — ticket confirmations, trip reminders; async by nature (likely a message queue rather than synchronous calls from the ticketing flow).
- **Admin Portal** — internal-facing UI/APIs for managing routes, fares, and viewing analytics; distinct auth/authorization needs from the rider-facing API.
- **Future React frontend** — a rider-facing web client built once the API contract is stable enough to build against without constant breakage.
- **Future deployment architecture** — moving from local Docker Compose to a real deployment target (e.g., a single VM with Compose as a first step, then likely a managed container platform or Kubernetes if/when multiple services exist).
- **Cloud deployment considerations** — managed Postgres vs. self-hosted, secrets management (replacing local `.env` files with a proper secrets manager), observability (logging/metrics/tracing), and CI/CD for automated build-test-deploy.

This roadmap will be revisited and refined as each sprint completes — treat it as directional, not a fixed spec.
