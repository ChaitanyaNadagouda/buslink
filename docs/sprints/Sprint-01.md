# Sprint 1

## Goal

Generate the Spring Boot project, establish the flat package structure, create all JPA
entities, connect to PostgreSQL, and verify the application starts clean with all tables
created. No business logic. No REST APIs. No authentication.

---

## Scope

- Spring Boot project generation and configuration
- Flat package structure under `com.buslink`
- All JPA entities created and verified in PostgreSQL, including `Payment` (added mid-sprint alongside `Transaction` — see S1-31)
- Common infrastructure: `BaseEntity`, `ApiResponse`, `GlobalExceptionHandler`, custom exceptions
- Stub security config (permit-all)
- Swagger UI reachable
- README setup instructions

**Moved to Sprint 2 (2026-07-07, approved):** repository and service layer (originally S1-28/S1-29). Rather than a minimal stub now and full implementation later, all repository/service work will be built together in Sprint 2 as one coherent unit.

**Out of scope:** JWT auth, QR generation, REST endpoints, business logic, Flyway migrations (deferred to post-LLD).

**Note (2026-07-05):** At generation time, Spring Initializr's default had moved to Spring Boot 4.1.0 (3.x is now the trailing legacy line, latest patch 3.5.16). Decided to generate on 4.1.0 rather than pin to the now-legacy 3.x — see `docs/ARCHITECTURE.md` for the resulting stack changes (starter renames, per-starter test artifacts, springdoc version). Tech decisions may continue to shift during implementation as newer/better options surface; when that happens, update this file and `ARCHITECTURE.md` immediately rather than waiting for sprint close.

---

## Tasks

### Setup
- [x] S1-01 — Generate Spring Boot project: Java 21, Maven, Spring Boot 4.1.0 (bumped from planned 3.x — see note below)
- [x] S1-02 — Add dependencies: `spring-boot-starter-webmvc` (renamed from `spring-boot-starter-web` in Boot 4), `spring-boot-starter-data-jpa`, `postgresql`, `spring-boot-starter-security`, `lombok`, `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui:3.0.3`
- [x] S1-03 — Verify clean build: `./mvnw clean install` — passed, jar produced at `target/buslink-backend-0.0.1-SNAPSHOT.jar`
- [x] S1-04 — Push to GitHub — repo: `buslink` (renamed from planned `buslink-backend`; the git root is the project root, not `backend/`, so the repo holds docs/, infrastructure/, and backend/ together as one monorepo)
- [x] S1-05 — Define branch strategy: `main` → `dev` → `feature/*`

### Package Structure
- [x] S1-06 — Scaffold flat top-level packages under `com.buslink`:
  ```
  controller/
  service/
    impl/
  repository/
  entity/
  enums/
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
- [x] S1-07 — Decided to keep the existing flat `application.properties` (no Spring Profiles) — simpler while learning, with only one environment (local dev) in play; revisit profiles when a second environment (staging/prod) actually exists
- [x] S1-08 — PostgreSQL already running via Docker Compose — database already exists as `buslink` (auto-created from `POSTGRES_DB` in `infrastructure/.env`); no separate `buslink_db` needed, so this task is satisfied as-is rather than creating a duplicate
- [x] S1-09 — Configure datasource in `application.properties` (`${POSTGRES_DB}`/`${POSTGRES_USER}`/`${POSTGRES_PASSWORD}` placeholders sourced from `infrastructure/.env`); verified — app starts clean, HikariCP connects to `jdbc:postgresql://localhost:5432/buslink`

### Entities
- [x] S1-10 — `User.java` in `entity/` — table `users` (see Definition of Done note on the `user`/reserved-keyword rename); fields: `userId` (UUID, generated), `name` (required), `email` (required, unique), `mobileNo` (required, unique — added for OTP login alongside email/password), `passwordHash` (required), `qrToken` (unique, nullable — issued post-registration), `deviceId` (no constraint), `status` (`UserStatus` enum in new `enums/` package: `ACTIVE`/`INACTIVE`/`SUSPENDED`), `createdAt` (`@CreationTimestamp`; will fold into `BaseEntity` at S1-23/24)
- [x] S1-11 — `Wallet.java` in `entity/`, extends `BaseEntity` — table `wallet`; fields: `walletId` (UUID, generated), `userId` (plain FK column, not an object relationship — see rationale in dev log), `balance` (`BigDecimal`, `precision=12,scale=2`, defaults to `0` via `@Builder.Default`), `status` (`WalletStatus` enum: `ACTIVE`/`FROZEN`/`DEACTIVATED`), `version` (`Long`, `@Version` for optimistic locking)
- [x] S1-12 — `Transaction.java` in `entity/`, extends `BaseEntity` — table `transaction`; fields: `transactionId` (UUID, generated), `userId` (plain FK), `amount` (`BigDecimal`, precision 12/scale 2), `type` (`TransactionType` enum: `DEBIT`/`CREDIT`), `status` (`TransactionStatus` enum: `PENDING`/`SUCCESS`/`FAILED`/`REVERSED`), `referenceId` (UUID, required — points to the `Ticket` or `Payment` that caused this ledger entry)
- [x] S1-31 (added mid-sprint, approved) — `Payment.java` in `entity/`, extends `BaseEntity` — table `payment`; new entity representing an external gateway payment (distinct from `Transaction`, which is only the internal wallet ledger). Pulls forward just the entity *shape* from `ARCHITECTURE.md`'s Future Roadmap "Payments" item — gateway integration and idempotency *logic* remain future work. Fields: `paymentId` (UUID, generated), `userId` (plain FK), `amount` (`BigDecimal`, precision 12/scale 2), `mode` (`PaymentMode` enum: `UPI`/`CARD`/`NETBANKING`), `purpose` (`PaymentPurpose` enum: `WALLET_TOPUP`/`TICKET_PAYMENT` — a top-up causes a `Transaction` CREDIT; a direct ticket payment does not touch the wallet at all), `status` (`PaymentStatus` enum: `PENDING`/`SUCCESS`/`FAILED`/`REFUNDED`), `gatewayReferenceId` (String, unique, nullable — the gateway's own reference; unique constraint enforces webhook idempotency), `referenceId` (UUID, nullable — points to the `Ticket` being paid for when `purpose=TICKET_PAYMENT`; null for `WALLET_TOPUP`)
- [x] S1-13 — `Route.java` in `entity/`, extends `BaseEntity` — table `route`; fields: `routeId` (UUID, generated), `routeName` (required, unique — e.g. "500K"), `originStop` (required), `destinationStop` (required). Changed from the originally planned `stops` field — see design discussion note below.

  **Design note (2026-07-06):** `stops` was deliberately dropped in favor of `originStop`/`destinationStop`. A `Route` here only needs to identify the physical route and satisfy the FK reference from `Bus`/`Ticket` — nothing in this sprint (no business logic, no fare calculation) consumes a full stop sequence. Modeling the real stop topology (ordered stops + per-stop stage, needed for the conductor-side fare-by-stage calculation) is deliberately deferred to whenever the Fare Service is actually built — see `ARCHITECTURE.md` roadmap. No Flyway migrations exist yet (`ddl-auto=create`/`update`), so reshaping this later costs nothing.
- [x] S1-14 — `Bus.java` in `entity/`, extends `BaseEntity` — table `bus`; fields: `busId` (UUID, generated), `busNumber` (required, unique), `routeId` (plain FK column, consistent with `Wallet`/`Transaction`/`Payment`)
- [x] S1-15 — `Conductor.java` in `entity/`, extends `BaseEntity` — table `conductor`; fields: `conductorId` (UUID, generated), `name` (required), `email` (required, unique), `passwordHash` (required), `deviceId` (no constraint), `busId` (plain FK, nullable — a conductor may be unassigned between shifts), `status` (own `ConductorStatus` enum: `ACTIVE`/`INACTIVE`/`SUSPENDED` — deliberately separate from `UserStatus` despite identical values, since conductor and rider lifecycles shouldn't be coupled to the same type)
- [x] S1-16 — `Ticket.java` in `entity/`, extends `BaseEntity` — table `ticket`; fields: `ticketId` (UUID, generated), `userId`/`conductorId`/`busId`/`routeId` (plain FK columns), `sourceStop`/`destinationStop` (required, plain strings — conductor enters these manually; no self-service origin/destination selection since buses have no entry/exit gates), `fare` (`BigDecimal`, precision 12/scale 2), `status` (`TicketStatus` enum: `ISSUED`/`PAID`/`EXPIRED`/`CANCELLED` — see design note below), `issuedAt` (`Instant`, required, client-settable — deliberately distinct from inherited `createdAt`; see design note)

  **Design note (2026-07-06) — `TicketStatus` and the real issuance flow:** the QR scan at boarding identifies *which rider's account* to issue the ticket to (buses are crowded with no entry/exit gates, so origin/destination can't be self-scanned like a metro) — the conductor manually enters the stops. The ticket then appears on the rider's phone and is paid for as a **separate, later step** (wallet or online mode via `Payment`/`Transaction`). This is why `TicketStatus` has both `ISSUED` (created, awaiting payment) and `PAID` (payment completed) as distinct states, with `EXPIRED` covering an issued-but-never-paid ticket (fare evasion) and `CANCELLED` for conductor/admin voids. Same reasoning applies to `issuedAt`: since a ticket can be created offline and synced later, `issuedAt` (real-world issuance time, client-controlled) must stay separate from `BaseEntity.createdAt` (server persistence time).
- [x] S1-17 — `SyncEvent.java` in `entity/`, extends `BaseEntity` — table `sync_event`; fields: `eventId` (UUID, generated), `entityType` (`SyncEntityType` enum — currently `TICKET` only; `Transaction`/`Payment` are never created offline since both inherently require live connectivity — wallet debit needs the server's live balance check, UPI/card needs the external gateway — so neither can produce a sync backlog), `entityId` (UUID, added mid-sprint, approved — indexed pointer to the specific record this event is about, so lookups/debugging don't need to reach into the JSON payload), `payload` (`Map<String, Object>`, mapped to Postgres `jsonb` via Hibernate 6-native `@JdbcTypeCode(SqlTypes.JSON)` — no extra dependency needed, already transitively available via `spring-boot-starter-data-jpa`), `status` (`SyncStatus` enum: `PENDING`/`SYNCED`/`FAILED`); `createdAt` via inherited `BaseEntity` (no separate client/server timing gap here, unlike `Ticket.issuedAt`)
- [x] S1-18 — `ddl-auto=create` (already set at S1-09); ran the app, verified via Hibernate's DDL log that all 9 tables were created cleanly: `users`, `wallet`, `transaction`, `payment`, `route`, `bus`, `conductor`, `ticket`, `sync_event`. App started with zero errors (`Started BusLinkApplication in 3.162 seconds`). Bonus finding: Hibernate 7 auto-generates Postgres `CHECK` constraints for every `@Enumerated(STRING)` column, enforcing valid enum values at the DB level too, not just in Java.

### Common Infrastructure
- [x] S1-19 — `ApiResponse.java` in `dto/response/` — implemented as a Java `record` (not Lombok — immutable pure data carrier, no persistence concerns, idiomatic on Java 21), fields `success`/`message`/`data`; static factories `success(data)`, `success(message, data)`, `error(message)`. Chosen as one uniform shape for both success and error responses (rather than mixing in RFC 7807 `ProblemDetail` for errors only), so `GlobalExceptionHandler` (S1-20) returns the same contract
- [x] S1-20 — `GlobalExceptionHandler.java` in `exception/` — `@RestControllerAdvice` (+ `@Slf4j`), handles 6 exception types total: `ResourceNotFoundException` (404), `ValidationException` (400), `MethodArgumentNotValidException` (400, added mid-sprint approved — Spring's own Bean Validation failure, returns field-level errors as a `Map<String,String>` in `ApiResponse.data`), `DataIntegrityViolationException` (409, added — relevant given the 6 unique constraints already added across entities this sprint), `ObjectOptimisticLockingFailureException` (409, added — relevant given `Wallet.version`), generic `Exception` (500, logs the real exception server-side via `log.error`, returns a safe generic message to the client — avoids leaking internals). All responses wrapped in `ApiResponse` (S1-19) uniformly, success and error alike.
- [x] S1-21 — `ResourceNotFoundException.java` (extends `RuntimeException`; structured constructor `(resourceName, fieldName, fieldValue)` auto-formats the message, plus a plain-message overload) and `ValidationException.java` (extends `RuntimeException`, plain message) in `exception/`
- [x] S1-22 — Verified Swagger UI works (`HTTP 200` at `http://localhost:8080/swagger-ui/index.html`), but currently gated behind Spring Security's default auto-configured auth (401 without credentials — no `SecurityConfig` exists yet). **Sequencing gap found:** this task was ordered before S1-26 (`SecurityConfig` permit-all), but Swagger UI can't be reached credential-free until S1-26 exists. Confirmed working with the dev-mode generated password from the startup log; will re-verify credential-free once S1-26 lands.
- [x] S1-23 — `BaseEntity.java` in `entity/` — `@MappedSuperclass` with `createdAt`, `updatedAt` via Hibernate-native `@CreationTimestamp` / `@UpdateTimestamp`; `@EnableJpaAuditing` intentionally skipped (that's for Spring Data's `@CreatedDate`/`@LastModifiedDate`/`@CreatedBy`/`@LastModifiedBy` mechanism, a separate thing from the Hibernate annotations actually used here — revisit if `@CreatedBy`/`@LastModifiedBy` are ever needed once auth exists). Moved earlier than planned (was going to follow S1-18) since every entity from here on needs it.
- [x] S1-24 — Make all entities extend `BaseEntity` (applies as each entity is created, starting with `User`)
- [x] S1-25 — `@Version` field already added to `Wallet.java` in S1-11 — verified (compiles, `@Version` present as `Long version`)

### Security & Config
- [x] S1-26 — `SecurityConfig.java` in `security/` — `@Bean SecurityFilterChain` (modern Spring Security 6.x style, not the deprecated `WebSecurityConfigurerAdapter`), CSRF disabled (correct for a stateless JWT-bound API, not a shortcut — CSRF defends against ambient cookie auth, which doesn't apply here), `SessionCreationPolicy.STATELESS` set now since the target architecture is already known, `anyRequest().permitAll()`. Verified: Swagger UI and `/v3/api-docs` both now return `HTTP 200` with zero credentials (previously 401, see S1-22 note).
- [x] S1-27 — `spring.jpa.hibernate.ddl-auto` switched from `create` to `update` in `application.properties`. Verified: app starts clean, zero DDL statements logged (schema already matches — `update` correctly made no changes since S1-18 already created everything), Swagger UI still returns `200`.

### Repository & Service Stub
- [~] S1-28 — **Moved to Sprint 2** (2026-07-07, approved) — `UserRepository.java` and `WalletRepository.java` in `repository/` extending `JpaRepository`
- [~] S1-29 — **Moved to Sprint 2** (2026-07-07, approved) — `UserServiceImpl.java` in `service/impl/` — minimal `findById()` to verify layer wiring

### Docs
- [x] S1-30 — `README.md` written: tech stack, prerequisites, clone/env-setup/Docker Compose/run instructions, Swagger verification, project structure. Includes the env-var-loading gotcha discovered during S1-18/S1-26 verification (`infrastructure/.env` is read by Docker Compose only, not by Spring Boot — must be sourced into the shell, or set in IntelliJ's Run Configuration, before `spring-boot:run`).

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

- [x] Spring Boot application starts with zero errors — verified repeatedly (S1-18, S1-26, S1-27)
- [x] All 9 tables visible in pgAdmin: `users` (renamed from `user` — reserved keyword in PostgreSQL), `wallet`, `transaction`, `payment` (added mid-sprint, S1-31), `route`, `bus`, `conductor`, `ticket`, `sync_event` — visually confirmed in pgAdmin UI (2026-07-08), in addition to the earlier Hibernate DDL log check (S1-18)
- [x] Swagger UI accessible at `/swagger-ui/index.html` — verified credential-free (S1-22, S1-26)
- [x] Flat package structure committed and matches the scaffold in S1-06 — committed `ae3d60e`, merged to `dev`
- [x] All entities in `entity/`, committed with correct JPA annotations (repositories moved to Sprint 2, see S1-28/S1-29 note) — committed `ae3d60e`, merged to `dev`
- [x] `BaseEntity` extended by all entities — verified in code
- [x] `@Version` present on `Wallet` entity — verified (S1-25)
- [x] `GlobalExceptionHandler` in `exception/`, `ApiResponse` in `dto/response/` — verified in code
- [x] `README.md` has working setup instructions — written (S1-30), individual steps (env loading, Docker Compose, Swagger URL) verified piecewise this sprint; not tested via an actual fresh clone end-to-end
- [x] All changes committed to `feature/project-setup` and merged into `dev` — `ae3d60e`, pushed to `origin/dev`
