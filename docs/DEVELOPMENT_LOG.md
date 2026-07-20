# DEVELOPMENT_LOG.md

This document records every significant milestone throughout the BusLink project. Append to it after every completed feature, sprint, or milestone.

---

# Sprint 0

## Development Environment

Completed

- Installed WSL Ubuntu 24.04
- Configured Linux development environment
- Installed Java 21
- Installed Maven
- Installed Git
- Configured GitHub SSH Authentication
- Installed Node.js LTS
- Installed npm
- Installed Claude Code
- Installed Docker Desktop
- Enabled Docker WSL Integration
- Configured IntelliJ to use WSL
- Created Developer workspace
- Created BusLink project structure

---

# Sprint 1

## Infrastructure

Completed

- Created Docker Compose
- Started PostgreSQL
- Started pgAdmin
- Verified database connectivity

Next

- ~~Generate Spring Boot project.~~ Done — see below.

## Spring Boot Project Generation

Completed

- Generated Spring Boot project via Initializr into `backend/` (Java 21, Maven, group `com.buslink`, artifact `buslink-backend`)
- Bumped target framework version from planned Spring Boot 3.x to **4.1.0** — 3.x had become the trailing legacy line by generation time; see `docs/ARCHITECTURE.md` for full rationale and resulting stack changes (starter renames, springdoc 3.0.3)
- Added Sprint 1 dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-security`, `lombok`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui:3.0.3`
- Verified clean build: `./mvnw clean install` succeeded, jar produced

Next

- Push `backend/` to GitHub (`buslink-backend` repo), define branch strategy (S1-04, S1-05).

## GitHub & Branch Strategy

Completed

- Pushed to GitHub as monorepo `buslink` (renamed from planned `buslink-backend`; git root is the project root, holding `docs/`, `infrastructure/`, and `backend/` together, not just `backend/`)
- Defined branch strategy: `main` → `dev` → `feature/*`

## Package Structure & Entities

Completed

- Scaffolded flat top-level packages under `com.buslink`: `controller`, `service`/`impl`, `repository`, `entity`, `enums`, `dto`/`request`/`response`, `security`, `config`, `exception`, `mapper`, `validator`, `util`, `event`, `scheduler`, `cache`
- Configured datasource in `application.properties` against the existing `buslink` database (no separate `buslink_db`); flat properties file, no Spring Profiles yet (revisit when a second environment exists)
- Created all 9 JPA entities extending `BaseEntity`: `User`, `Wallet`, `Transaction`, `Payment` (added mid-sprint, S1-31 — represents an external gateway payment, distinct from the internal wallet ledger `Transaction`), `Route`, `Bus`, `Conductor`, `Ticket`, `SyncEvent`
- Notable design calls made along the way: `Route` stores only `originStop`/`destinationStop` (full stop-topology modeling deferred to the Fare Service sprint); `Ticket` models `ISSUED`→`PAID` as distinct states with a separate client-controlled `issuedAt` (offline issuance, online payment as a later step); `SyncEvent` scoped to `TICKET` only since `Transaction`/`Payment` inherently require live connectivity
- `ddl-auto=create` run, verified via Hibernate DDL log: all 9 tables created cleanly (`users`, `wallet`, `transaction`, `payment`, `route`, `bus`, `conductor`, `ticket`, `sync_event`), app started with zero errors

## Common Infrastructure & Security Stub

Completed

- `ApiResponse` (record, `dto/response/`) as one uniform success/error response shape
- `GlobalExceptionHandler` (`exception/`) handling 6 exception types, all wrapped in `ApiResponse`
- `ResourceNotFoundException`, `ValidationException` (`exception/`)
- `BaseEntity` (`@MappedSuperclass`, `createdAt`/`updatedAt` via Hibernate `@CreationTimestamp`/`@UpdateTimestamp`) — all entities extend it
- `SecurityConfig` permit-all stub (`security/`) — modern `SecurityFilterChain` bean style, CSRF disabled, `STATELESS` session policy set now even though JWT itself is Sprint 2 scope; Swagger UI and `/v3/api-docs` verified reachable credential-free
- `ddl-auto` switched from `create` to `update`, verified zero-diff restart

## Sprint 1 Closure

Completed

- `README.md` written: tech stack, prerequisites, setup instructions, Swagger verification, project structure, plus the `infrastructure/.env`-is-Compose-only-not-Spring-Boot gotcha
- All Sprint 1 work committed and merged: `feature/project-setup` → `dev` (`ae3d60e`), pushed to `origin/dev`
- Final Definition of Done item closed (2026-07-08): all 9 tables visually confirmed in pgAdmin, in addition to the earlier Hibernate DDL log check
- **Sprint 1 declared complete (2026-07-08).** Repository/service layer (S1-28/S1-29) carried forward to Sprint 2 rather than stubbed twice.

---

# Sprint 2

## Auth & JWT

Started (2026-07-08)

- Sprint 2 plan approved; `docs/sprints/Sprint-02.md` created as the active sprint file, `DEVELOPMENT_ROADMAP.md` updated to point to it
- Scope: carry over `UserRepository`/`WalletRepository`/`UserServiceImpl` stub (S1-28/S1-29), JWT infrastructure, locked-down `SecurityConfig`, passenger register/login/refresh, QR token generation, `UserController` (`/user/profile`, `/user/qr`)
- Next: S1-28 (`UserRepository`)
