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

## Postman Verification & Bug Fixes (2026-07-20)

Completed

- S2-21: built `postman/BusLink-API.postman_collection.json` — `Auth` (Register, Login,
  Refresh) and `User` (Profile, QR Token) folders, `baseUrl`/`accessToken` collection
  variables, Login's Tests script auto-captures `accessToken` for chained requests.
  Imported and run from Postman Desktop.
- S2-22: full verification pass against a live app (IntelliJ run config + EnvFile
  plugin loading `infrastructure/.env`) and DB (Docker Compose). All 5 requests
  confirmed working end-to-end, including direct pgAdmin checks on both `users`
  (`qr_token`) and `wallet` (`balance=0`, `status=ACTIVE`) rather than inferring the
  latter from transaction atomicity.
- **Bug found:** unauthenticated requests to protected endpoints returned `403`
  instead of `401`. Root cause: no `AuthenticationEntryPoint` was configured in
  `SecurityConfig` — with no `httpBasic()`/`formLogin()` enabled either, Spring
  Security had nothing to challenge with and silently defaulted to
  `Http403ForbiddenEntryPoint`. Fixed with a new `JwtAuthenticationEntryPoint`
  (`security/`) returning a `401` in the standard `ApiResponse` shape.
- **Gotcha hit while fixing the above:** constructor-injecting
  `com.fasterxml.jackson.databind.ObjectMapper` (classic Jackson 2) failed at startup —
  Spring Boot 4.1's `spring-boot-starter-jackson` autoconfigures a bean of the new
  **Jackson 3** type (`tools.jackson.databind.ObjectMapper`, new groupId/package) as
  its default, not Jackson 2. The Jackson 2 classes on the classpath come from
  `jjwt-jackson` (S2-01) for jjwt's own internal use only — never Spring-managed.
  Logged in `ARCHITECTURE.md` alongside the other Boot-4-vs-Boot-3 gotchas.
- **Bug found:** duplicate-email registration returned `400` instead of `409`. Root
  cause: `AuthServiceImpl.register()` threw `ValidationException`, which
  `GlobalExceptionHandler` unconditionally maps to `400` — semantically wrong for a
  conflict-with-existing-state case. Fixed by adding `ConflictException`
  (`exception/`) mapped to `409`, used only for this case; `login()`/`refreshToken()`
  keep their existing `ValidationException`/`400` usage unchanged, since those are
  genuinely bad requests.
- Sprint-02.md and its Definition of Done updated to reflect all of the above,
  verified individually rather than assumed from the task list.
- Next: S2-23 (`AuthServiceImplTest` unit tests), then S2-24 (merge `feature/auth` into `dev`)

## Unit Tests & Sprint Closure (2026-07-21)

Completed

- S2-23: `AuthServiceImplTest.java` (`src/test/java/com/buslink/service/impl/`) —
  plain Mockito unit test (`@ExtendWith(MockitoExtension.class)`, `@Mock`/
  `@InjectMocks`, no Spring context, same reasoning as `JwtUtilTest`), 5 tests:
  `register_success`, `register_duplicateEmail`, `login_success`,
  `login_wrongPassword`, `login_inactiveUser`. `userRepository.save()` stubbed via
  `thenAnswer` to set the generated `userId` onto the passed-in entity and return
  it, mirroring real Hibernate behavior for `GenerationType.UUID`.
  **Deviation (discussed, approved):** `register_duplicateEmail` asserts
  `ConflictException`, not the `ValidationException` the original task text named —
  the real code has thrown `ConflictException` since the S2-22 bug fix, so the test
  follows current behavior, not stale task wording. Verified:
  `./mvnw test -Dtest=AuthServiceImplTest` — 5/5 pass; full `./mvnw test` run
  alongside it, `JwtUtilTest` still 6/6, only pre-existing failure is
  `BusLinkApplicationTests.contextLoads` (no DB/Docker in this shell, unrelated).
- S2-24: committed S2-22 bug fixes + S2-23 tests + docs on the feature branch
  (`a60d4ef`), merged into `dev` with `--no-ff` (`4b7af17`), `dev` pushed to
  `origin/dev`. Feature branch's remote copy left one commit behind — disposable
  after merge, no PR workflow in use for this project (see `PROJECT_CONTEXT.md`
  notes on branch strategy). Verified: `./mvnw compile` clean on `dev` post-merge.
- **Sprint 2 declared complete (2026-07-21).** All Definition of Done items
  verified individually: register/login/refresh issuing JWT tokens, QR token
  generation, secured profile/QR endpoints, both S2-22 bug fixes (403→401,
  400→409), Swagger UI still reachable, all 5 Postman requests passing, all 5
  `AuthServiceImplTest` unit tests passing, `feature/auth` merged into `dev` with
  a clean build.

---

# Sprint 3

## S3-19 Live Verification Closure (2026-07-26)

Completed

- S3-01 through S3-18 (Route/RouteStop/Ticket entity updates, 4 repositories,
  all request/response DTOs, role-based security including `JwtAccessDeniedHandler`,
  and `ConductorServiceImpl`/`ConductorController`) were already implemented
  and compile-verified — see `docs/sprints/Sprint-03.md` for full detail on
  each, including the two gaps caught and fixed mid-sprint (403 responses
  bypassing `ApiResponse`, and `/admin/**` being deliberately unreachable
  pending real admin auth).
- S3-19's live end-to-end verification (login → profile) had been blocked by
  the local Docker daemon hanging mid-attempt, with no test data written.
  Closed out this session: confirmed Docker responsive again (`docker ps`
  clean), inserted a temporary manual seed via `psql` (1 Route "500K", 1 Bus
  "KA-01-F-1234", 1 Conductor `conductor@buslink.com` / BCrypt("Test@1234")) —
  matching S3-29's planned `DataSeeder` shape exactly, since that seeder
  doesn't exist yet.
- Ran the app locally (`./mvnw spring-boot:run`) against the live Postgres
  container and verified with `curl`: `POST /conductor/auth/login` → `200`
  with access/refresh tokens and correctly populated `busId`/`routeId`;
  `GET /conductor/profile` (Bearer token) → `200` with correct conductor
  data; same endpoint with no token → `401` via `JwtAuthenticationEntryPoint`.
- Stopped the app and deleted the temporary route/bus/conductor rows
  afterward so `route` stays empty — `DataSeeder` (S3-29) only seeds when
  `route` is empty, so leaving manual data in place would have silently
  skipped it later.
- S3-19 fully closed. Next: S3-20 (`RouteService` interface).

## Route/Fare/Bus Service Layer & Controllers (2026-07-26)

Completed

- S3-20/S3-21: `RouteService`/`RouteServiceImpl` — `createRoute` (uniqueness
  check, duplicate-stop validation, `@Transactional` route+stops save),
  `getRouteById`/`getAllRoutes`, `updateRoute` (partial fare/status),
  `deleteRoute` (soft delete via status flip — never hard delete, since
  buses/tickets reference `routeId`). Gap-filled `UpdateRouteRequestDTO`
  (S3-09 only created the create-side DTO).
- S3-22/S3-23: `FareService`/`FareServiceImpl` — stop listing, prefix search,
  forward-only search, and stage-based fare calculation
  (`BigDecimal`/`RoundingMode.CEILING` on child fare).
- **Bug found and fixed:** `RouteStopRepository`'s search method (S3-06) was
  declared `findByRouteIdAndStopNameContainingIgnoreCaseOrderByStopSequenceAsc`
  (substring match) instead of `StartingWith` (prefix match). Traced through
  the planned Postman assertion for `search=H` (expects only 4 stops) against
  all 29 seeded stop names and found 9 others contain an "h" mid-name (e.g.
  `Banashankari`, `Jayadeva Hospital`) that `Containing` would have wrongly
  matched. Renamed to `StartingWith`; added one new combined derived query
  (`GreaterThan` + `StartingWith`) for the forward-search case, replacing an
  unused sequence-only method.
- S3-24/S3-25: `BusService`/`BusServiceImpl` — `createBus` (uniqueness +
  route-existence checks), `getAllBuses`, `getBusByBusId`. Accepted a known
  N+1 `Route` lookup per bus in `getAllBuses` given the domain's real fleet
  size (a handful of buses per route).
- S3-26/S3-27/S3-28: `AdminRouteController`, `AdminBusController`,
  `RouteController`. Along the way, found `RouteService`/`RouteServiceImpl`
  had no method backing S3-26's "add stop to an existing route" endpoint —
  added `addStop(UUID, CreateRouteStopDTO)`: append-only (`stopSequence`
  must equal `totalStops + 1`), plus a stage-consistency check (new stop's
  `stageNumber` must be `>=` the current last stop's) caught during design
  review — `stopSequence` alone only proves the new stop claims the next
  ordinal slot, not that it's geographically last; a lower `stageNumber`
  means the stop belongs in the middle of the route, which would need
  renumbering everything after it (out of scope for this endpoint).
  `RouteController`'s single `/stops` endpoint branches on which optional
  query params (`search`, `after`) are present, since all three stop-listing
  behaviors share one path.
- Verified throughout: `./mvnw compile clean` after each addition, plus full
  app boots against live Postgres to confirm Spring Data parses every new/
  renamed derived query method without a `PropertyReferenceException`
  (compile success alone can't catch that class of failure).
- Next: S3-29 (`DataSeeder`).

## Seed Data, Unit Tests, Postman Verification (2026-07-26)

Completed

- S3-29: `DataSeeder` (`@Component`, `ApplicationRunner`) seeds Route 500K,
  all 29 stops, 1 test bus, 1 test conductor — guarded by
  `routeRepository.count() > 0`. Verified both that seeding produces exactly
  the specified data (route fields, all 29 stops spot-checked at both ends,
  bus, conductor) and that a second app restart against the now-populated DB
  does *not* reseed (row counts unchanged) — confirming the guard actually
  prevents re-seeding, not just that it worked once.
- S3-30: `FareServiceImplTest` (5 tests, exactly as specified) and
  `RouteServiceImplTest` (8 tests). The latter was added beyond the
  original task text — Scope listed `RouteServiceImplTest` but neither a
  task number nor a Definition of Done line existed for it; confirmed with
  the user and added it here rather than leave a declared-in-scope item
  quietly unfulfilled. `RouteServiceImplTest` includes a dedicated
  `addStop_lowerStageNumber_throwsValidation` test directly exercising the
  stage-consistency bug caught during S3-26's design review.
- Verified: `./mvnw test -Dtest=FareServiceImplTest,RouteServiceImplTest` —
  13/13 pass; full `./mvnw test` — 26/26 pass (including
  `BusLinkApplicationTests.contextLoads`, which needs
  `infrastructure/.env` sourced into the same shell running Maven).
- S3-31: added `Conductor Auth`, `Route`, `Fare` folders to the Postman
  collection (plus an `Admin` folder, not in the original named-folder list,
  to cover the no-token-on-`/admin/**` check the DoD requires) — every new
  request has a test script asserting the expected result, same pattern as
  the existing passenger `Auth > Login`. Verified end-to-end in Postman
  Desktop against the live seeded data: conductor login (tokens +
  non-null `routeId`), all 29 stops in sequence order, exact stop-name
  matches for both search variants, correct fare calculation
  (`stagesCrossed=6`, `totalFare=90.00`), `403` for a passenger token on a
  conductor endpoint, `401` for no token on `/admin/routes` — all passed.
- **Sprint 3 declared complete (2026-07-26).** All Definition of Done items
  verified individually: `route`/`route_stop`/`ticket` schema changes
  confirmed, conductor login returning tokens with non-null `routeId`, all
  stop/fare Postman checks passing against the seeded Route 500K,
  `ROLE_PASSENGER` correctly rejected (`403`) on conductor endpoints,
  no-token `/admin/**` correctly rejected (`401`), all 13 unit tests
  passing, all Postman assertions passing. `feature/route-fare-conductor-auth`
  to be merged into `dev` next (S3-32).
