# Sprint 1

## Goal

Generate the Spring Boot project, establish the flat package structure, create all JPA
entities, connect to PostgreSQL, and verify the application starts clean with all tables
created. No business logic. No REST APIs. No authentication.

---

## Scope

- Spring Boot project generation and configuration
- Flat package structure under `com.buslink`
- All JPA entities created and verified in PostgreSQL
- Common infrastructure: `BaseEntity`, `ApiResponse`, `GlobalExceptionHandler`, custom exceptions
- Stub security config (permit-all)
- Swagger UI reachable
- Minimal repository and service layer to verify wiring
- README setup instructions

**Out of scope:** JWT auth, QR generation, REST endpoints, business logic, Flyway migrations (deferred to post-LLD).

**Note (2026-07-05):** At generation time, Spring Initializr's default had moved to Spring Boot 4.1.0 (3.x is now the trailing legacy line, latest patch 3.5.16). Decided to generate on 4.1.0 rather than pin to the now-legacy 3.x — see `docs/ARCHITECTURE.md` for the resulting stack changes (starter renames, per-starter test artifacts, springdoc version). Tech decisions may continue to shift during implementation as newer/better options surface; when that happens, update this file and `ARCHITECTURE.md` immediately rather than waiting for sprint close.

---

## Tasks

### Setup
- [x] S1-01 — Generate Spring Boot project: Java 21, Maven, Spring Boot 4.1.0 (bumped from planned 3.x — see note below)
- [x] S1-02 — Add dependencies: `spring-boot-starter-webmvc` (renamed from `spring-boot-starter-web` in Boot 4), `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-security`, `lombok`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui:3.0.3`
- [x] S1-03 — Verify clean build: `./mvnw clean install` — passed, jar produced at `target/buslink-backend-0.0.1-SNAPSHOT.jar`
- [ ] S1-04 — Push to GitHub — repo: `buslink` (renamed from planned `buslink-backend`; the git root is the project root, not `backend/`, so the repo holds docs/, infrastructure/, and backend/ together as one monorepo)
- [ ] S1-05 — Define branch strategy: `main` → `dev` → `feature/*`

### Package Structure
- [ ] S1-06 — Scaffold flat top-level packages under `com.buslink`:
  ```
  controller/
  service/
    impl/
  repository/
  entity/
  dto/
    request/
    response/
  security/
  config/
  exception/
  mapper/
  validator/
  util/
  event/
  scheduler/
  cache/
  BusLinkApplication.java
  ```

### Configuration
- [ ] S1-07 — Create `application.yml` and `application-dev.yml` with dev profile
- [ ] S1-08 — PostgreSQL already running via Docker Compose — create `buslink_db` database in pgAdmin
- [ ] S1-09 — Configure datasource in `application-dev.yml`, verify DB connection on startup

### Entities
- [ ] S1-10 — `User.java` in `entity/` — fields: `userId` (UUID), `name`, `email`, `passwordHash`, `qrToken`, `deviceId`, `status`, `createdAt`
- [ ] S1-11 — `Wallet.java` in `entity/` — fields: `walletId`, `userId` (FK), `balance` (BigDecimal), `status`, `@Version` for optimistic locking
- [ ] S1-12 — `Transaction.java` in `entity/` — fields: `transactionId`, `userId`, `amount`, `type` (DEBIT/CREDIT), `status`, `referenceId`, `createdAt`
- [ ] S1-13 — `Route.java` in `entity/` — fields: `routeId`, `routeName`, `stops`
- [ ] S1-14 — `Bus.java` in `entity/` — fields: `busId`, `busNumber`, `routeId` (FK)
- [ ] S1-15 — `Conductor.java` in `entity/` — fields: `conductorId`, `name`, `email`, `passwordHash`, `deviceId`, `busId` (FK), `status`
- [ ] S1-16 — `Ticket.java` in `entity/` — fields: `ticketId`, `userId`, `conductorId`, `busId`, `routeId`, `sourceStop`, `destinationStop`, `fare`, `status`, `issuedAt`
- [ ] S1-17 — `SyncEvent.java` in `entity/` — fields: `eventId`, `entityType`, `payload` (JSON), `status` (PENDING/SYNCED/FAILED), `createdAt`
- [ ] S1-18 — Set `ddl-auto=create`, run app, verify all 8 tables created in pgAdmin

### Common Infrastructure
- [ ] S1-19 — `ApiResponse.java` in `dto/response/` — generic wrapper: `success`, `message`, `data`
- [ ] S1-20 — `GlobalExceptionHandler.java` in `exception/` — `@RestControllerAdvice`, handles `ResourceNotFoundException`, `ValidationException`, `Exception`
- [ ] S1-21 — `ResourceNotFoundException.java` and `ValidationException.java` in `exception/`
- [ ] S1-22 — Verify Swagger UI loads at `http://localhost:8080/swagger-ui/index.html`
- [ ] S1-23 — `BaseEntity.java` in `entity/` — `@MappedSuperclass` with `createdAt`, `updatedAt` via `@CreationTimestamp` / `@UpdateTimestamp`, enable `@EnableJpaAuditing` on main class
- [ ] S1-24 — Make all entities extend `BaseEntity`
- [ ] S1-25 — `@Version` field already added to `Wallet.java` in S1-11 — verify

### Security & Config
- [ ] S1-26 — `SecurityConfig.java` in `security/` — permit all requests for now
- [ ] S1-27 — Switch `ddl-auto` to `update` after table verification

### Repository & Service Stub
- [ ] S1-28 — `UserRepository.java` and `WalletRepository.java` in `repository/` extending `JpaRepository`
- [ ] S1-29 — `UserServiceImpl.java` in `service/impl/` — minimal `findById()` to verify layer wiring

### Docs
- [ ] S1-30 — Update `README.md` — clone instructions, DB setup, how to run

---

## Dependencies

| Dependency | Status |
|---|---|
| WSL Ubuntu 24.04 | ✅ Done |
| IntelliJ configured with WSL | ✅ Done |
| Java 21 | ✅ Done |
| Maven | ✅ Done |
| Git + GitHub SSH + GitHub CLI | ✅ Done |
| Docker Desktop + WSL integration | ✅ Done |
| PostgreSQL via Docker Compose | ✅ Done |
| pgAdmin connected to PostgreSQL | ✅ Done |
| LLD / DB schema finalized | ⏳ Pending — entities in this sprint are drafts; fields may be revised post-LLD |

---

## Definition of Done

- [ ] Spring Boot application starts with zero errors
- [ ] All 8 tables visible in pgAdmin: `user`, `wallet`, `transaction`, `route`, `bus`, `conductor`, `ticket`, `sync_event`
- [ ] Swagger UI accessible at `/swagger-ui/index.html`
- [ ] Flat package structure committed and matches the scaffold in S1-06
- [ ] All entities in `entity/`, repositories in `repository/`, committed with correct JPA annotations
- [ ] `BaseEntity` extended by all entities
- [ ] `@Version` present on `Wallet` entity
- [ ] `GlobalExceptionHandler` in `exception/`, `ApiResponse` in `dto/response/`
- [ ] `README.md` has working setup instructions
- [ ] All changes committed to `feature/project-setup` and merged into `dev`
