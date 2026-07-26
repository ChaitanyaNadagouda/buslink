# Sprint 3

## Goal

Introduce the Route domain end-to-end: update the `Route` entity, create the new
`RouteStop` entity, implement Conductor auth (separate from passenger), build
Route + Stop + Fare + Conductor APIs, seed Route 500K with all 29 stops, and
lock down endpoints with role-based security. By end of sprint a conductor can
login, fetch their route's stops, search stops by prefix, calculate fare between
any two stops, and the admin can create/manage routes and buses.

---

## Scope

- `Route` entity updated — new fields: `routeNumber`, `farePerStage`, `totalStops`,
  `originStop` (denormalized), `destinationStop` (denormalized), `status` (RouteStatus)
- `RouteStop` entity — new table: `route_stop` with `stopName`, `stopSequence`,
  `stageNumber`, FK to `route`
- `Ticket` entity updated — new fields: `adultCount`, `childCount`, `infantCount`,
  `adultFare`, `childFare`, `totalFare`, `stagesCrossed`; old `fare` field removed
- `RouteStatus` enum — new: ACTIVE / INACTIVE
- Conductor auth — separate login flow, `ROLE_CONDUCTOR` JWT, `ConductorRepository`,
  `ConductorServiceImpl`
- Role-based security — `ROLE_PASSENGER`, `ROLE_CONDUCTOR`, `ROLE_ADMIN` wired
  into `SecurityConfig`
- Route APIs — CRUD under `/admin/routes` (ADMIN only)
- RouteStop APIs — add/list stops under `/admin/routes/{routeId}/stops` (ADMIN only)
- Stop search APIs — conductor-facing dropdown endpoints (CONDUCTOR only)
- Fare calculation API — `GET /routes/{routeId}/fare` (CONDUCTOR only)
- Bus APIs — CRUD under `/admin/buses` (ADMIN only)
- Conductor assignment — `PUT /admin/conductors/{conductorId}/assign-bus`
- Seed data — Route 500K with all 29 stops + stage numbers seeded via `DataSeeder`
- Unit tests — `FareServiceImplTest`, `RouteServiceImplTest`

**Out of scope:** Ticket issuance flow (Sprint 4), wallet deduction (Sprint 4),
payment flows (Sprint 5), Redis caching of routes/fare (Sprint 6), Flyway (deferred).

---

## Tasks

### Entity Updates

- [x] S3-01 — Update `Route.java` in `entity/` — add fields:
  - `routeNumber` (VARCHAR, unique, not null) — e.g. "500K"
  - `farePerStage` (NUMERIC 8,2, not null) — e.g. 6.00
  - `totalStops` (INTEGER, not null) — denormalized count
  - `originStop` (VARCHAR, not null) — denormalized first stop name
  - `destinationStop` (VARCHAR, not null) — denormalized last stop name
  - `status` (RouteStatus enum, not null) — ACTIVE / INACTIVE
  - Remove old `stops` field if present from Sprint 1 draft — none existed, no-op
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

- [x] S3-02 — Create `RouteStatus.java` in `enums/` — ACTIVE, INACTIVE
  - Implemented ahead of S3-01 in practice — `Route.status`'s type depends on
    this enum existing to compile at all, so the two were done together.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

- [x] S3-03 — Create `RouteStop.java` in `entity/` — new entity:
  - `routeStopId` (UUID, PK, generated)
  - `routeId` (UUID, plain FK — consistent with codebase pattern)
  - `stopName` (VARCHAR, not null)
  - `stopSequence` (INTEGER, not null)
  - `stageNumber` (INTEGER, not null)
  - Extends `BaseEntity`
  - Table: `route_stop`
  - Unique constraints: `(route_id, stop_sequence)`, `(route_id, stop_name)`
  - Indexes: dropped as a separate declaration — a unique constraint already
    creates its own backing btree index in Postgres, so a same-column `@Index`
    would just duplicate it
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app started clean against
    the live Postgres container; confirmed via `psql \d route_stop` — table
    created with both unique constraints present as btree indexes

- [x] S3-04 — Update `Ticket.java` in `entity/` — add fields:
  - `stagesCrossed` (INTEGER, not null)
  - `adultCount` (INTEGER, not null, default 0)
  - `childCount` (INTEGER, not null, default 0)
  - `infantCount` (INTEGER, not null, default 0)
  - `adultFare` (NUMERIC 12,2, not null)
  - `childFare` (NUMERIC 12,2, not null)
  - `totalFare` (NUMERIC 12,2, not null)
  - Remove old `fare` field (rename → `totalFare`)
  - `adultCount`/`childCount`/`infantCount` default via `@Builder.Default = 0`,
    same pattern as `Wallet.balance` — Java-level default only, no DB-level
    `@ColumnDefault` (consistent with the rest of the codebase)
  - `ticket` table was empty (0 rows), so `ddl-auto=update` added all 7 new
    NOT NULL columns cleanly with no backfill needed
  - Note confirmed correct: `ddl-auto=update` did NOT drop `fare` — manually
    ran `ALTER TABLE ticket DROP COLUMN fare;` via psql after verifying the
    new columns existed
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app started clean
    against the live Postgres container; `psql \d ticket` confirms all 7 new
    columns present and `fare` gone

### Repositories

- [x] S3-05 — `RouteRepository.java` in `repository/` — extend
  `JpaRepository<Route, UUID>`:
  - `Optional<Route> findByRouteNumber(String routeNumber)`
  - `List<Route> findByStatus(RouteStatus status)`

- [x] S3-06 — `RouteStopRepository.java` in `repository/` — extend
  `JpaRepository<RouteStop, UUID>`:
  - `List<RouteStop> findByRouteIdOrderByStopSequenceAsc(UUID routeId)`
  - `Optional<RouteStop> findByRouteIdAndStopName(UUID routeId, String stopName)`
  - `List<RouteStop> findByRouteIdAndStopNameStartingWithIgnoreCaseOrderByStopSequenceAsc(UUID routeId, String search)`
  - `List<RouteStop> findByRouteIdAndStopSequenceGreaterThanAndStopNameStartingWithIgnoreCaseOrderByStopSequenceAsc(UUID routeId, Integer sequence, String search)`
  - **Bug found and fixed during S3-23 (2026-07-26):** originally declared with
    `...ContainingIgnoreCase...` (substring-anywhere match) instead of
    `...StartingWithIgnoreCase...` (prefix match). Traced through the S3-31
    Postman expectations for `search=H` (expects only HBR/HSR/Hebbal/Hennur)
    and found 9 other seeded stops contain an "h" mid-name (e.g.
    `Banashankari`, `Jayadeva Hospital`, `Marathahalli Bridge`) that
    `Containing` would have wrongly matched. Renamed to `StartingWith`, which
    produces exactly the expected 4. Also replaced the sequence-only
    `findByRouteIdAndStopSequenceGreaterThanOrderByStopSequenceAsc` (never
    called by anything) with a single combined derived query doing both the
    sequence-forward and name-prefix filtering at the DB level, for
    `searchStopsAfter` (S3-23) — keeps filtering consistent with every other
    stop lookup (pushed down to the repository) rather than fetching broad
    and filtering in the service.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; also booted the app
    (`./mvnw spring-boot:run`, live Postgres) to confirm Spring Data parses
    both the renamed and new derived query names without a
    `PropertyReferenceException` — `compile clean` alone can't catch that
    class of failure, only a context boot can.

- [x] S3-07 — `ConductorRepository.java` in `repository/` — extend
  `JpaRepository<Conductor, UUID>`:
  - `Optional<Conductor> findByEmail(String email)`
  - `Optional<Conductor> findByBusId(UUID busId)`

- [x] S3-08 — `BusRepository.java` in `repository/` — extend
  `JpaRepository<Bus, UUID>`:
  - `Optional<Bus> findByBusNumber(String busNumber)`
  - `List<Bus> findByRouteId(UUID routeId)`
  - Verified all 4 repositories: `./mvnw compile clean` — BUILD SUCCESS

### DTOs

- [x] S3-09 — Request DTOs in `dto/request/` (Java records):
  - `CreateRouteRequestDTO` — routeNumber (@NotBlank), routeName (@NotBlank),
    farePerStage (@NotNull, @DecimalMin="0.1"), stops (List<CreateRouteStopDTO>)
  - `CreateRouteStopDTO` — stopName (@NotBlank), stopSequence (@NotNull, @Min=1),
    stageNumber (@NotNull, @Min=1)
  - `CreateBusRequestDTO` — busNumber (@NotBlank), routeId (@NotNull)
  - `AssignConductorRequestDTO` — busId (@NotNull)
  - `ConductorLoginRequestDTO` — email (@NotBlank, @Email), password (@NotBlank)
  - Added `@NotEmpty @Valid` on `CreateRouteRequestDTO.stops` beyond what the
    plan specified — without `@Valid` on the nested list, `@NotBlank`/`@Min`
    on each `CreateRouteStopDTO` element would never actually run (Bean
    Validation doesn't cascade into collections automatically); `@NotEmpty`
    rejects a route submitted with zero stops before it ever reaches the
    service layer

- [x] S3-10 — Response DTOs in `dto/response/` (Java records):
  - `RouteResponseDTO` — routeId, routeNumber, routeName, originStop,
    destinationStop, totalStops, farePerStage, status
  - `RouteStopResponseDTO` — routeStopId, stopName, stopSequence, stageNumber
  - `FareResponseDTO` — originStop, destinationStop, stagesCrossed,
    adultFare, childFare, infantFare (always 0), totalFare
    (totalFare computed from passed adult/child/infant counts)
  - `BusResponseDTO` — busId, busNumber, routeId, routeNumber (joined)
  - `ConductorResponseDTO` — conductorId, name, email, busId, status
  - `ConductorAuthResponseDTO` — accessToken, refreshToken, conductorId,
    name, email, busId, routeId (derived from bus)
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

### Security — Role-Based

- [x] S3-11 — Update `UserDetailsServiceImpl.java` in `security/` —
  already grants `ROLE_PASSENGER` to all User rows. No change needed here —
  conductor auth uses a separate principal path (S3-12).

- [x] S3-12 — Create `ConductorPrincipal.java` in `security/` — implements
  `UserDetails`, wraps `Conductor`:
  - `getAuthorities()` → `ROLE_CONDUCTOR`
  - `getUsername()` → conductor email
  - `isEnabled()` → `ConductorStatus.ACTIVE`
  - Same pattern as `UserPrincipal` from Sprint 2

- [x] S3-13 — Update `JwtUtil.java` in `security/` — add role claim to token:
  - On `generateAccessToken()` — embed `role` claim: "PASSENGER" or "CONDUCTOR"
    (pass role as parameter or derive from principal type)
  - Add `extractRole(String token)` method
  - Add `generateConductorAccessToken(Conductor conductor)` and
    `generateConductorRefreshToken(Conductor conductor)`
  - Update `JwtUtilTest` — add test for role claim round-trip
  - Decision: role baked into which method is called
    (`generateAccessToken`/`generateConductorAccessToken`) rather than a raw
    `String role` parameter on one shared method — every Sprint 2 call site
    in `AuthServiceImpl` stays unchanged, and a caller can't pass the wrong
    role string for a given principal type
  - Verified: `./mvnw test -Dtest=JwtUtilTest` — 7/7 pass

- [x] S3-14 — Update `JwtAuthenticationFilter.java` in `security/` —
  after validating token, extract role claim and set correct principal type:
  - If role = "PASSENGER" → load via `UserDetailsServiceImpl` (existing)
  - If role = "CONDUCTOR" → load via new `ConductorDetailsServiceImpl`
  - Decision: injected the two concrete classes directly instead of the
    shared `UserDetailsService` interface — both implementations share one
    interface with one method, so injecting by interface type gives Spring
    two ambiguous candidates for one field (`NoUniqueBeanDefinitionException`
    at startup). Concrete-class injection resolves the ambiguity with zero
    extra ceremony (`@Qualifier` was the alternative, rejected as unnecessary
    ceremony for exactly two fixed, known implementations)
  - Also added `/conductor/auth/**` to `shouldNotFilter` alongside the
    existing `/auth/**` — same reasoning as Sprint 2: `permitAll()` in
    `SecurityConfig` already covers authorization, this just avoids wasted
    JWT-parsing work on a login request
  - Token missing a role claim entirely (any already-issued Sprint 2 token)
    falls through to the passenger path rather than failing — preserves
    backward compatibility with tokens minted before this claim existed
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app started clean
    against live Postgres, no bean wiring conflicts

- [x] S3-15 — Create `ConductorDetailsServiceImpl.java` in `security/` —
  implements `UserDetailsService`:
  - `loadUserByUsername(String email)` → load from `ConductorRepository`
  - Returns `ConductorPrincipal`
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

- [x] S3-16 — Update `SecurityConfig.java` in `security/` — add
  role-based rules:
  ```
  /admin/**           → ROLE_ADMIN only
  /conductor/**       → ROLE_CONDUCTOR only
  /routes/*/stops     → ROLE_CONDUCTOR (dropdown APIs)
  /routes/*/fare      → ROLE_CONDUCTOR (fare preview)
  /auth/**            → permit all (existing)
  /conductor/auth/**  → permit all (new conductor login)
  /swagger-ui/**      → permit all (existing)
  /v3/api-docs/**     → permit all (existing)
  everything else     → authenticated
  ```
  - Deviated from the plan's literal `/conductor/auth/**` wildcard — used an
    explicit `POST /conductor/auth/login` matcher instead, matching the
    tighter-scoping convention `/auth/register|login|refresh` already uses
    (named POST paths, not a blanket `/auth/**` permitAll)
  - Ordering matters: `authorizeHttpRequests` matches rules top-to-bottom,
    first match wins — `/conductor/auth/login` permitAll is declared *before*
    `/conductor/**` → `hasRole("CONDUCTOR")`, otherwise the broader rule would
    shadow the login endpoint's public access
  - **Known gap, deliberately deferred:** `/admin/**` → `hasRole("ADMIN")` is
    permanently unreachable in Sprint 3 — no task in this sprint creates an
    admin login/JWT-issuance path (no `AdminPrincipal`, no admin credentials).
    Route 500K, its stops, the test bus, and the test conductor are all
    created directly by `DataSeeder` (S3-29), not through the admin API.
    Decided to ship the rule as planned rather than scope-expand this sprint
    with a bare-bones admin auth flow — same pattern as Sprint 1 setting
    `SessionCreationPolicy.STATELESS` before JWT existed. Real admin auth is
    explicit future scope (Sprint 4+), not an oversight.
  - **Gap found and fixed the same sprint:** role-mismatch requests (valid
    JWT, wrong role) were falling through to Spring Security's default
    `AccessDeniedHandler`, which does **not** go through the project's
    `ApiResponse` envelope — every other error response in the API
    (`GlobalExceptionHandler`, `JwtAuthenticationEntryPoint`) is wrapped, so
    a bare 403 would have been the one inconsistent shape in the whole API.
    The plan's own Postman check (S3-31) only asserts the status code, not
    the body, so this wouldn't have failed verification — caught by
    reasoning about consistency, not by a failing test. Fixed by adding
    `JwtAccessDeniedHandler` (mirrors `JwtAuthenticationEntryPoint` exactly)
    and wiring it via `.exceptionHandling(ex -> ex.authenticationEntryPoint(...)
    .accessDeniedHandler(...))`. Verified live: registered a passenger,
    hit `GET /admin/routes` with their token → `403
    {"success":false,"message":"Access is denied","data":null}`; confirmed
    the no-token 401 path (both `/admin/routes` and the existing Sprint 2
    `/user/profile`) still returns the unchanged `JwtAuthenticationEntryPoint`
    body.
  - Also noticed (harmless): Spring logs
    `Found 2 UserDetailsService beans ... Global Authentication Manager will
    not use a UserDetailsService for username/password login` at startup —
    expected, since neither `AuthServiceImpl.login()` nor the upcoming
    `ConductorServiceImpl.login()` ever go through Spring's global
    `AuthenticationManager`/`DaoAuthenticationProvider` (both call
    `passwordEncoder.matches()` directly, per the Sprint 2 `login()` design)
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app started clean
    against live Postgres

### Conductor Auth Service

- [x] S3-17 — Create `ConductorService.java` interface in `service/`
- [x] S3-18 — Create `ConductorServiceImpl.java` in `service/impl/`:
  - `login(ConductorLoginRequestDTO)`:
    1. Load conductor by email — throw `ValidationException("Invalid email or password")`
       if not found (same user-enumeration prevention as Sprint 2 passenger login)
    2. Verify BCrypt password match — same generic error if wrong
    3. Check `ConductorStatus.ACTIVE` — throw `ValidationException` if not
    4. Derive `routeId` from `conductor.busId` → `busRepository.findById(busId)`
    5. Generate tokens via `JwtUtil.generateConductorAccessToken()`
    6. Return `ConductorAuthResponseDTO` with tokens + busId + routeId
  - `getConductorProfile(UUID conductorId)` → load + map to `ConductorResponseDTO`
  - `assignBus(UUID conductorId, UUID busId)`:
    1. Verify conductor exists
    2. Verify bus exists
    3. Update `conductor.busId`
    4. Save and return `ConductorResponseDTO`
  - Added a guard not spelled out in the plan: `conductor.getBusId() == null`
    throws `ValidationException("Conductor is not assigned to a bus")` before
    calling `busRepository.findById(...)`. `Conductor.busId` is nullable in
    the schema, so a conductor with no bus assigned is a real reachable
    state, not hypothetical — without the guard, `findById(null)` would
    surface as an unhelpful generic 500 instead of a clear 400.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS

- [x] S3-19 — Create `ConductorController.java` in `controller/`:
  - `POST /conductor/auth/login` → `ConductorServiceImpl.login()`
    → `ApiResponse<ConductorAuthResponseDTO>` (permit all)
  - `GET /conductor/profile` → `ConductorServiceImpl.getConductorProfile()`
    → `ApiResponse<ConductorResponseDTO>` (ROLE_CONDUCTOR)
  - Verified: `./mvnw compile clean` — BUILD SUCCESS
  - **Live end-to-end verification closed (2026-07-26):** Docker daemon
    confirmed responsive again. Inserted a temporary manual seed (1 Route
    "500K", 1 Bus "KA-01-F-1234", 1 Conductor `conductor@buslink.com` /
    BCrypt("Test@1234")) directly via `psql` — no `DataSeeder` (S3-29) exists
    yet, and this data matches its planned shape exactly. Ran the app
    (`./mvnw spring-boot:run` against the live Postgres container) and
    verified with `curl`:
    - `POST /conductor/auth/login` → `200`, access + refresh tokens returned,
      `busId` and `routeId` both correctly populated in the response
    - `GET /conductor/profile` (Bearer token) → `200`, correct conductor data
    - `GET /conductor/profile` (no token) → `401` (via `JwtAuthenticationEntryPoint`)
    - App stopped and the temporary route/bus/conductor rows deleted
      afterward, so `route` stays empty for S3-29's `DataSeeder` (which only
      seeds when `route` is empty) to run cleanly on its own later.

### Route Service

- [x] S3-20 — Create `RouteService.java` interface in `service/`:
  - `createRoute(CreateRouteRequestDTO)`, `getRouteById(UUID)`,
    `getAllRoutes()`, `updateRoute(UUID, UpdateRouteRequestDTO)`,
    `deleteRoute(UUID)`
  - Gap found: no DTO existed yet for the fare/status-only partial update
    `updateRoute` needs (S3-09 only created `CreateRouteRequestDTO`). Created
    `UpdateRouteRequestDTO` (`dto/request/`) — `farePerStage` (`@DecimalMin`,
    nullable) and `status` (nullable), both optional since it's a partial
    update; Bean Validation constraints skip null values, so `@DecimalMin`
    only fires when a fare is actually supplied.
  - Verified: `./mvnw compile` — BUILD SUCCESS
- [x] S3-21 — Create `RouteServiceImpl.java` in `service/impl/`:
  - `createRoute(CreateRouteRequestDTO)`:
    1. Check `routeNumber` uniqueness — throws `ConflictException` if duplicate
    2. Validates no duplicate `stopSequence` or `stopName` within the request
       (`ValidationException` — a malformed request, not a conflict with
       existing DB state, so it follows the Sprint 2 `ValidationException`
       precedent rather than `ConflictException`)
    3. Saves `Route` — `originStop`/`destinationStop` derived from the first/
       last stop in the submitted list (as literally specified), `totalStops`
       from list size, `status` = ACTIVE
    4. Saves all `RouteStop` rows via `saveAll`
    5. Returns `RouteResponseDTO`
    6. `@Transactional` — Route + all stops succeed or fail together
  - `getRouteById(UUID routeId)` → throws `ResourceNotFoundException` if absent
  - `getAllRoutes()` → list of `RouteResponseDTO`
  - `updateRoute(UUID routeId, UpdateRouteRequestDTO)` — updates `farePerStage`
    and/or `status` only where non-null (partial update; stop changes handled
    separately via stop endpoints)
  - `deleteRoute(UUID routeId)` → soft delete: sets status = INACTIVE
    (never hard delete — tickets reference routeId)
  - Manual DTO↔entity mapping (no MapStruct mapper yet), same pattern as
    `ConductorServiceImpl` — private `toResponseDTO` helper, no separate
    `mapper/` class for a single-direction 8-field mapping this simple
  - Verified: `./mvnw compile clean` — BUILD SUCCESS
  - **Gap found and fixed during S3-26 (2026-07-26):** `addStop(UUID routeId,
    CreateRouteStopDTO request)` added — S3-26's "add stop to an existing
    route" endpoint had no backing service method anywhere in S3-20/21's
    original scope. Behavior:
    1. Route must exist (`ResourceNotFoundException`); no existing stop with
       the same name on the route (`ConflictException`)
    2. **Append-only**: `stopSequence` must equal `route.totalStops + 1`
       (`ValidationException` otherwise) — a route is a physically ordered
       line of stops, and inserting into the middle would require renumbering
       every stop after it, which this endpoint was never scoped to do
    3. **Stage-consistency check** (caught during design review, not in the
       original plan): the new stop's `stageNumber` must be `>=` the current
       last stop's `stageNumber`. `stopSequence` alone only proves the new
       stop claims the next ordinal slot — it says nothing about whether it's
       geographically last. A stop submitted with a lower `stageNumber` than
       the current last stop is meant to sit in the *middle* of the route
       (same renumbering problem as above), not at the end, even though its
       `stopSequence` would pass check #2. Same-stage appends are allowed
       (matches the seeded 2-stops-per-stage pattern); only a strictly lower
       stage is rejected (`ValidationException`).
    4. On success, updates the denormalized `Route.totalStops` (+1) and
       `Route.destinationStop` (to the new stop's name) — both would silently
       drift stale otherwise, since `createRoute` is no longer the only path
       that can add a `RouteStop`.
    - Verified: `./mvnw compile clean` — BUILD SUCCESS

### RouteStop Service (Fare Engine)

- [x] S3-22 — Create `FareService.java` interface in `service/`:
  - `getStopsForRoute`, `searchStops`, `searchStopsAfter`, `calculateFare` —
    signatures matching S3-23 below

- [x] S3-23 — Create `FareServiceImpl.java` in `service/impl/`:
  - `getStopsForRoute(UUID routeId)`:
    → `List<RouteStopResponseDTO>` ordered by `stopSequence` ASC
    → Used by conductor app at login to cache full stop list for offline use
  - `searchStops(UUID routeId, String search)`:
    → All stops on route where `stopName` starts with `search` (prefix,
      case-insensitive) — see the S3-06 fix note above
    → Used for origin dropdown (all stops on route)
  - `searchStopsAfter(UUID routeId, String originStop, String search)`:
    1. Look up origin's `RouteStop` via `findByRouteIdAndStopName` →
       `ResourceNotFoundException` if absent
    2. Single combined derived query: `stopSequence > origin.stopSequence`
       AND name starts with `search`
    → Used for destination dropdown (only stops after origin)
  - `calculateFare(UUID routeId, String originStop, String destinationStop,
    int adults, int children, int infants)`:
    1. Fetch origin `RouteStop` → `ResourceNotFoundException` if not found
    2. Fetch destination `RouteStop` → `ResourceNotFoundException` if not found
    3. Validate destination sequence > origin sequence →
       `ValidationException("Destination must be after origin")` if not
    4. `stagesCrossed = (destStage - originStage) + 1`
    5. `adultFare = stagesCrossed × route.farePerStage` (`route` fetched via
       `RouteRepository.findById`)
    6. `childFare = adultFare / 2`, `BigDecimal.divide(2, 2, RoundingMode.CEILING)`
    7. `infantFare = BigDecimal.ZERO`
    8. `totalFare = (adults × adultFare) + (children × childFare)`
    9. Returns `FareResponseDTO`
  - Manual DTO↔entity mapping (no MapStruct), same pattern as
    `RouteServiceImpl`/`ConductorServiceImpl`
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app boot confirmed no
    derived-query parsing errors (see S3-06 note)

### Admin — Bus Service

- [x] S3-24 — Create `BusService.java` interface in `service/`:
  - `createBus`, `getAllBuses`, `getBusByBusId`

- [x] S3-25 — Create `BusServiceImpl.java` in `service/impl/`:
  - `createBus(CreateBusRequestDTO)`:
    1. Checks `busNumber` uniqueness — throws `ConflictException` if duplicate
    2. Verifies `routeId` exists — throws `ResourceNotFoundException` if not
    3. Saves and returns `BusResponseDTO`
  - `getAllBuses()` → list of `BusResponseDTO`
  - `getBusByBusId(UUID busId)` → throws `ResourceNotFoundException` if absent
  - `Bus` only stores a plain `routeId` UUID (no JPA relationship, consistent
    with the rest of the codebase), so populating `BusResponseDTO.routeNumber`
    needs a separate `Route` lookup per bus. **Known trade-off:** `getAllBuses()`
    does one `Route` lookup per bus (N+1) — accepted for now given the
    domain's real fleet size (a handful of buses per route, no pagination
    planned), same reasoning as the monolith-over-microservices call in
    `ARCHITECTURE.md`. Revisit (e.g. batch-fetch routes by ID into a map)
    only if bus count actually grows large enough to matter.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS (no new derived-query
    methods this task — `BusRepository` unchanged from S3-08 — so no app-boot
    re-verification needed)

### Controllers

- [x] S3-26 — Create `AdminRouteController.java` in `controller/`
  (ADMIN role only — all endpoints under `/admin/routes`):
  - `POST   /admin/routes`                      → createRoute
  - `GET    /admin/routes`                      → getAllRoutes
  - `GET    /admin/routes/{routeId}`            → getRouteById
  - `PUT    /admin/routes/{routeId}/status`     → updateRoute
  - `POST   /admin/routes/{routeId}/stops`      → addStop (see S3-21 gap note)
  - `GET    /admin/routes/{routeId}/stops`      → `FareService.getStopsForRoute` (admin view)
  - All bodies annotated with `@Valid`
  - **Deviation:** `PUT .../status` is named for "activate/deactivate" in the
    plan, but wired to the already-existing `UpdateRouteRequestDTO` (fare
    and/or status, both optional/partial) rather than a status-only DTO — no
    separate fare-only endpoint exists anywhere in this sprint's controller
    list, so reusing what `RouteServiceImpl.updateRoute` already supports
    avoids either inventing an unplanned endpoint or leaving that method's
    fare-editing half with no HTTP entry point at all.
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app booted against live
    Postgres, confirmed no ambiguous-mapping/bean errors; `curl` sanity check:
    `GET /admin/routes` with no token → `401`

- [x] S3-27 — Create `AdminBusController.java` in `controller/`
  (ADMIN role only — spans two base paths per the plan, so no class-level
  `@RequestMapping`, full path on each method instead):
  - `POST   /admin/buses`                              → createBus
  - `GET    /admin/buses`                              → getAllBuses
  - `GET    /admin/buses/{busId}`                      → getBusByBusId
  - `PUT    /admin/conductors/{conductorId}/assign-bus` → `ConductorService.assignBus`
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app boot clean

- [x] S3-28 — Create `RouteController.java` in `controller/`
  (CONDUCTOR role only — conductor-facing stop + fare APIs):
  - `GET /routes/{routeId}/stops` — single method branches on which optional
    query params are present, since all three behaviors share one path:
    - neither `search` nor `after` → `FareService.getStopsForRoute()` (full
      list for offline cache)
    - `search` only → `FareService.searchStops()` (origin dropdown)
    - `after` present → `FareService.searchStopsAfter()` (destination
      dropdown); `search` defaults to `""` if omitted so "just picked origin,
      haven't typed anything yet" still returns the full forward list
      (`StartingWith("")` matches everything)
  - `GET /routes/{routeId}/fare?origin=X&destination=Y&adults=2&children=1&infants=1`
    → `FareService.calculateFare()` (fare preview before issuing)
  - Verified: `./mvnw compile clean` — BUILD SUCCESS; app booted against live
    Postgres, confirmed no mapping conflicts; `curl` sanity checks: both
    `/routes/{id}/stops` and `/routes/{id}/fare` with no token → `401`

### Seed Data

- [x] S3-29 — Create `DataSeeder.java` in `config/` — `@Component`,
  implements `ApplicationRunner`. Seeds on startup only if `route` table is empty:
  - Route 500K: routeNumber="500K", routeName="Banashankari to Hebbal",
    farePerStage=6.00, status=ACTIVE
  - All 29 stops with correct stopSequence (1–29) and stageNumber (1–15)
    as per the finalized stage mapping:
    ```
    seq=1,  stage=1  → Banashankari Bus Station
    seq=2,  stage=1  → Sangam Circle
    seq=3,  stage=2  → Jayanagar 5th Block
    seq=4,  stage=2  → Aurobindo Circle (JP Nagar)
    seq=5,  stage=3  → Ragigudda
    seq=6,  stage=3  → Jayadeva Hospital
    seq=7,  stage=4  → BTM Layout
    seq=8,  stage=4  → Central Silk Board
    seq=9,  stage=5  → HSR Layout
    seq=10, stage=5  → Agara Junction
    seq=11, stage=6  → Iblur
    seq=12, stage=6  → Bellandur
    seq=13, stage=7  → Devarabisanahalli
    seq=14, stage=7  → Kadabisanahalli
    seq=15, stage=8  → Marathahalli Bridge
    seq=16, stage=8  → Karthiknagar
    seq=17, stage=9  → Doddanekkundi
    seq=18, stage=9  → Mahadevapura
    seq=19, stage=10 → KR Puram Railway Station
    seq=20, stage=10 → Tin Factory
    seq=21, stage=11 → Kasturi Nagar
    seq=22, stage=11 → Babusab Palya
    seq=23, stage=12 → Kalyan Nagar
    seq=24, stage=12 → Hennur Junction
    seq=25, stage=13 → HBR Layout
    seq=26, stage=13 → Nagavara Junction (Manyata Tech Park)
    seq=27, stage=14 → Veeranna Palya
    seq=28, stage=14 → Kempapura
    seq=29, stage=15 → Hebbal Bridge
    ```
  - Also seed: 1 test Bus (busNumber="KA-01-F-1234", routeId=500K-id)
  - Also seed: 1 test Conductor (email="conductor@buslink.com",
    password=BCrypt("Test@1234"), busId=test-bus-id, status=ACTIVE)
  - Guard is `routeRepository.count() > 0` (checked first, returns early) —
    Route is the seed's root; if it exists, everything downstream from it
    (stops/bus/conductor) was already seeded too.
  - Stop data modeled as a private `record SeedStop(stopSequence, stageNumber,
    stopName)` + a `List.of(...)` of all 29, rather than 29 separate
    `RouteStop.builder()` calls — keeps the seed data itself (the part that
    might need editing later) visually separate from the seeding logic.
  - Verified (2026-07-26): confirmed `route`/`route_stop`/`bus`/`conductor`
    all empty first, booted the app against live Postgres, then via `psql`:
    `route` — 1 row, exactly matching spec (farePerStage=6.00, totalStops=29,
    originStop/destinationStop correct, status=ACTIVE); `route_stop` — 29
    rows, first-5/last-5 checked against the stage mapping table above,
    exact match; `bus` — 1 row (`KA-01-F-1234`) correctly linked to the
    route; `conductor` — 1 row correctly linked to the bus, `ACTIVE`.
    **Idempotency also verified**: restarted the app a second time against
    the now-populated DB — row counts unchanged (1/29/1/1), confirming the
    empty-check guard actually prevents re-seeding, not just that it seeds
    once. This seed data is left in place (unlike the throwaway manual
    insert used for the S3-19 verification) — it's exactly what S3-31's
    Postman sequence will run against.

### Testing & Verification

- [x] S3-30 — Unit tests: `FareServiceImplTest.java` in `src/test/`:
  - Mock: `RouteStopRepository`, `RouteRepository`
  - `calculateFare_sameStage` — origin=dest stage → stagesCrossed=1, minimum fare
  - `calculateFare_multipleStages` — HSR(5) → KR Puram(10) →
    stagesCrossed=6, adultFare=36, childFare=18, totalFare=90
    (2 adults + 1 child + 1 infant)
  - `calculateFare_destinationBeforeOrigin` → ValidationException thrown
  - `calculateFare_invalidStop` → ResourceNotFoundException thrown
  - `searchStopsAfter_returnsOnlyForwardStops` — stops before origin excluded
  - Verified: `./mvnw test -Dtest=FareServiceImplTest` — 5/5 pass
  - **Scope gap found and closed (2026-07-26):** the sprint's own Scope
    section (top of this file) lists `RouteServiceImplTest` alongside
    `FareServiceImplTest`, but no task number or Definition of Done line was
    ever created for it — confirmed with the user and added it to this task
    rather than leave a declared-in-scope item quietly unfulfilled. 8 tests
    added covering `RouteServiceImpl`: `createRoute_success`,
    `createRoute_duplicateRouteNumber`, `createRoute_duplicateStopNameInRequest`,
    `updateRoute_partialFareUpdate`, `deleteRoute_setsStatusInactive`,
    `addStop_success`, `addStop_wrongSequence_throwsValidation`,
    `addStop_lowerStageNumber_throwsValidation` (the last one directly
    exercises the stage-consistency bug caught during S3-21's design review).
  - Verified: `./mvnw test -Dtest=FareServiceImplTest,RouteServiceImplTest` —
    13/13 pass; full `./mvnw test` afterward — 26/26 pass, including
    `BusLinkApplicationTests.contextLoads` (needs `infrastructure/.env`
    sourced into the shell running Maven — each Bash invocation starts fresh
    and doesn't inherit an earlier `source`, so this fails if env vars aren't
    re-sourced in the same command; not a code issue, same pattern noted in
    Sprint 2)

- [ ] S3-31 — Postman verification sequence:
  - `POST /conductor/auth/login` (seeded conductor credentials)
    → expect 200, tokens returned, routeId non-null in response
  - `GET /routes/{routeId}/stops` (conductor Bearer token)
    → expect 200, 29 stops returned in sequence order
  - `GET /routes/{routeId}/stops?search=H` (conductor token)
    → expect: HBR Layout, HSR Layout, Hebbal Bridge, Hennur Junction
  - `GET /routes/{routeId}/stops?after=HSR Layout&search=K` (conductor token)
    → expect: Kadabisanahalli, Karthiknagar, KR Puram Railway Station,
      Kasturi Nagar, Kalyan Nagar, Kempapura (NOT Banashankari etc.)
  - `GET /routes/{routeId}/fare?origin=HSR Layout
    &destination=KR Puram Railway Station&adults=2&children=1&infants=1`
    → expect: stagesCrossed=6, adultFare=36, childFare=18, totalFare=90
  - `GET /routes/{routeId}/stops` (passenger Bearer token)
    → expect 403 (ROLE_PASSENGER cannot access CONDUCTOR endpoints)
  - `POST /admin/routes` (no token) → expect 401
  - Add Conductor Auth, Route, Fare folders to Postman collection

### Git

- [x] S3-32 — Commit and merge `feature/route-fare-conductor-auth` into `dev`
  - All sprint closure docs (`PROJECT_CONTEXT.md`, `DEVELOPMENT_LOG.md`,
    `ARCHITECTURE.md`, `API.md`, `INTERVIEW_PREP.md`, `DEVELOPMENT_ROADMAP.md`)
    updated and committed on the feature branch itself, before merging —
    per explicit request, a deliberate change from Sprint 1/2's pattern of
    writing the closure commit on `dev` right after merging.
  - Merged with `--no-ff` into `dev`, no conflicts.
  - Verified post-merge: `./mvnw clean test` — 26/26 pass (one transient
    flaky failure on the first run, confirmed not a regression — see the
    Definition of Done note below for the full trace).

---

## Dependencies

| Dependency | Status |
|---|---|
| Spring Boot 4.1.0 project | ✅ Done (Sprint 1) |
| All base entities + enums | ✅ Done (Sprint 1) |
| JWT infrastructure (JwtUtil, Filter) | ✅ Done (Sprint 2) |
| UserPrincipal, UserDetailsServiceImpl | ✅ Done (Sprint 2) |
| ConflictException, GlobalExceptionHandler | ✅ Done (Sprint 2) |
| Passenger auth (register/login) | ✅ Done (Sprint 2) |
| SecurityConfig (currently no role rules) | ✅ Done (Sprint 2) — updated S3-16 |
| LLD schema finalized | ✅ Done (pre-Sprint 3 discussion) |
| RouteStop entity designed | ✅ Done (pre-Sprint 3 discussion) |

---

## Definition of Done

- [x] `route` table has new columns: `route_number`, `fare_per_stage`,
      `total_stops`, `origin_stop`, `destination_stop`, `status` — confirmed
      via `psql \d route` (2026-07-26)
- [x] `route_stop` table exists with 29 rows for Route 500K visible in
      pgAdmin — confirmed via `psql` (`SELECT count(*) FROM route_stop` = 29,
      same live DB pgAdmin connects to)
- [x] `ticket` table has new columns: `stages_crossed`, `adult_count`,
      `child_count`, `infant_count`, `adult_fare`, `child_fare`, `total_fare`;
      old `fare` column manually dropped — confirmed via `psql \d ticket`,
      no standalone `fare` column present
- [x] `POST /conductor/auth/login` returns tokens with `routeId` in response
      — verified live in S3-19's closure and again in Postman Desktop (S3-31)
- [x] `GET /routes/{routeId}/stops` returns 29 stops in sequence order —
      Postman Desktop, all assertions green
- [x] `GET /routes/{routeId}/stops?search=H` returns only H-matching stops —
      Postman Desktop, exact 4-stop match asserted and passed
- [x] `GET /routes/{routeId}/stops?after=HSR Layout&search=K` returns only
      forward stops matching K — Postman Desktop, exact 6-stop match passed
- [x] `GET /routes/{routeId}/fare` with HSR→KR Puram, 2A+1C+1I returns
      stagesCrossed=6, totalFare=90.00 — Postman Desktop, passed
- [x] ROLE_PASSENGER cannot access `/routes/**` — returns 403 — Postman
      Desktop, passed
- [x] No token on `/admin/**` — returns 401 — Postman Desktop, passed
- [x] All 5 `FareServiceImplTest` unit tests pass — confirmed, 5/5
- [x] All 8 `RouteServiceImplTest` unit tests pass (added to Scope but missing
      from this list originally — see S3-30 note) — confirmed, 8/8
- [x] All 8 Postman verification calls pass — confirmed live in Postman
      Desktop by the project owner (2026-07-26)
- [x] `feature/route-fare-conductor-auth` merged into `dev`, build clean —
      merged, `./mvnw clean test` — 26/26 pass on `dev` post-merge. One
      transient failure hit on the first post-merge run
      (`JwtUtilTest.isTokenValid_returnsFalse_forTamperedToken`, alongside an
      anomalous 470s runtime for an unrelated pure-Mockito test in the same
      run) — re-ran in isolation and as part of the full suite twice more,
      passed cleanly both times. Root cause of the test's occasional
      flakiness: it tampers with a JWT by flipping only its last base64
      character, but base64's 6-bits-per-character packing means the last
      character of a signature doesn't always encode bits that affect the
      decoded byte value — an unlucky token can have its last character
      flipped without the underlying signature bytes actually changing, so
      verification spuriously passes. Pre-existing Sprint 2 test, untouched
      by Sprint 3; not fixed here (out of this sprint's scope), but worth
      flagging for a future sprint since a flaky test is a real (if rare)
      liability.
- [ ] All 8 Postman verification calls pass
- [ ] `feature/route-fare-conductor-auth` merged into `dev`, build clean
