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
  - `List<RouteStop> findByRouteIdAndStopNameContainingIgnoreCaseOrderByStopSequenceAsc(UUID routeId, String search)`
  - `List<RouteStop> findByRouteIdAndStopSequenceGreaterThanOrderByStopSequenceAsc(UUID routeId, Integer sequence)`

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

- [ ] S3-11 — Update `UserDetailsServiceImpl.java` in `security/` —
  already grants `ROLE_PASSENGER` to all User rows. No change needed here —
  conductor auth uses a separate principal path (S3-12).

- [ ] S3-12 — Create `ConductorPrincipal.java` in `security/` — implements
  `UserDetails`, wraps `Conductor`:
  - `getAuthorities()` → `ROLE_CONDUCTOR`
  - `getUsername()` → conductor email
  - `isEnabled()` → `ConductorStatus.ACTIVE`
  - Same pattern as `UserPrincipal` from Sprint 2

- [ ] S3-13 — Update `JwtUtil.java` in `security/` — add role claim to token:
  - On `generateAccessToken()` — embed `role` claim: "PASSENGER" or "CONDUCTOR"
    (pass role as parameter or derive from principal type)
  - Add `extractRole(String token)` method
  - Add `generateConductorAccessToken(Conductor conductor)` and
    `generateConductorRefreshToken(Conductor conductor)`
  - Update `JwtUtilTest` — add test for role claim round-trip
  - Verify: `./mvnw test -Dtest=JwtUtilTest` — all pass

- [ ] S3-14 — Update `JwtAuthenticationFilter.java` in `security/` —
  after validating token, extract role claim and set correct principal type:
  - If role = "PASSENGER" → load via `UserDetailsServiceImpl` (existing)
  - If role = "CONDUCTOR" → load via new `ConductorDetailsServiceImpl`
  - Verify: `./mvnw compile clean`

- [ ] S3-15 — Create `ConductorDetailsServiceImpl.java` in `security/` —
  implements `UserDetailsService`:
  - `loadUserByUsername(String email)` → load from `ConductorRepository`
  - Returns `ConductorPrincipal`
  - Verify: `./mvnw compile clean`

- [ ] S3-16 — Update `SecurityConfig.java` in `security/` — add
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
  - Verify: `./mvnw compile clean`

### Conductor Auth Service

- [ ] S3-17 — Create `ConductorService.java` interface in `service/`
- [ ] S3-18 — Create `ConductorServiceImpl.java` in `service/impl/`:
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
  - Verify: `./mvnw compile clean`

- [ ] S3-19 — Create `ConductorController.java` in `controller/`:
  - `POST /conductor/auth/login` → `ConductorServiceImpl.login()`
    → `ApiResponse<ConductorAuthResponseDTO>` (permit all)
  - `GET /conductor/profile` → `ConductorServiceImpl.getConductorProfile()`
    → `ApiResponse<ConductorResponseDTO>` (ROLE_CONDUCTOR)
  - Verify: `./mvnw compile clean`, app starts clean

### Route Service

- [ ] S3-20 — Create `RouteService.java` interface in `service/`
- [ ] S3-21 — Create `RouteServiceImpl.java` in `service/impl/`:
  - `createRoute(CreateRouteRequestDTO)`:
    1. Check `routeNumber` uniqueness — throw `ConflictException` if duplicate
    2. Save `Route` — derive `originStop` from first stop in list,
       `destinationStop` from last, `totalStops` from list size,
       `status` = ACTIVE
    3. Save all `RouteStop` rows in order — validate no duplicate
       `stopSequence` or `stopName` in the same request
    4. Return `RouteResponseDTO`
    5. `@Transactional` — Route + all stops succeed or fail together
  - `getRouteById(UUID routeId)` → throw `ResourceNotFoundException` if absent
  - `getAllRoutes()` → list of `RouteResponseDTO`
  - `updateRoute(UUID routeId, ...)` → update fare/status only
    (stop changes handled separately via stop endpoints)
  - `deleteRoute(UUID routeId)` → soft delete: set status = INACTIVE
    (never hard delete — tickets reference routeId)
  - Verify: `./mvnw compile clean`

### RouteStop Service (Fare Engine)

- [ ] S3-22 — Create `FareService.java` interface in `service/`
- [ ] S3-23 — Create `FareServiceImpl.java` in `service/impl/`:
  - `getStopsForRoute(UUID routeId)`:
    → `List<RouteStopResponseDTO>` ordered by `stopSequence` ASC
    → Used by conductor app at login to cache full stop list for offline use
  - `searchStops(UUID routeId, String search)`:
    → All stops on route where `stopName ILIKE search%`
    → Used for origin dropdown (all stops on route)
  - `searchStopsAfter(UUID routeId, String originStop, String search)`:
    1. Find origin's `stopSequence`
    2. Find all stops with `stopSequence > originSequence` AND name matches search
    → Used for destination dropdown (only stops after origin)
  - `calculateFare(UUID routeId, String originStop, String destinationStop,
    int adults, int children, int infants)`:
    1. Fetch origin `RouteStop` → throw `ResourceNotFoundException` if not found
    2. Fetch destination `RouteStop` → throw `ResourceNotFoundException` if not found
    3. Validate destination sequence > origin sequence
       → throw `ValidationException("Destination must be after origin")` if not
    4. `stagesCrossed = (destStage - originStage) + 1`
    5. `adultFare = stagesCrossed × route.farePerStage`
    6. `childFare = adultFare / 2` (ceiling, `RoundingMode.CEILING`)
    7. `infantFare = 0`
    8. `totalFare = (adults × adultFare) + (children × childFare)`
    9. Return `FareResponseDTO`
  - Verify: `./mvnw compile clean`

### Admin — Bus Service

- [ ] S3-24 — Create `BusService.java` interface in `service/`
- [ ] S3-25 — Create `BusServiceImpl.java` in `service/impl/`:
  - `createBus(CreateBusRequestDTO)`:
    1. Check `busNumber` uniqueness — throw `ConflictException` if duplicate
    2. Verify `routeId` exists — throw `ResourceNotFoundException` if not
    3. Save and return `BusResponseDTO`
  - `getAllBuses()` → list of `BusResponseDTO`
  - `getBusByBusId(UUID busId)` → throw `ResourceNotFoundException` if absent
  - Verify: `./mvnw compile clean`

### Controllers

- [ ] S3-26 — Create `AdminRouteController.java` in `controller/`
  (ADMIN role only — all endpoints under `/admin/routes`):
  - `POST   /admin/routes`                      → createRoute
  - `GET    /admin/routes`                      → getAllRoutes
  - `GET    /admin/routes/{routeId}`            → getRouteById
  - `PUT    /admin/routes/{routeId}/status`     → activate/deactivate route
  - `POST   /admin/routes/{routeId}/stops`      → add stop to existing route
  - `GET    /admin/routes/{routeId}/stops`      → list all stops on route (admin view)
  - All bodies annotated with `@Valid`

- [ ] S3-27 — Create `AdminBusController.java` in `controller/`
  (ADMIN role only — all endpoints under `/admin/buses`):
  - `POST   /admin/buses`                              → createBus
  - `GET    /admin/buses`                              → getAllBuses
  - `GET    /admin/buses/{busId}`                      → getBusByBusId
  - `PUT    /admin/conductors/{conductorId}/assign-bus` → assignBus

- [ ] S3-28 — Create `RouteController.java` in `controller/`
  (CONDUCTOR role only — conductor-facing stop + fare APIs):
  - `GET /routes/{routeId}/stops`
    → `FareServiceImpl.getStopsForRoute()` (full list for offline cache)
  - `GET /routes/{routeId}/stops?search=H`
    → `FareServiceImpl.searchStops()` (origin dropdown)
  - `GET /routes/{routeId}/stops?after=HSR Layout&search=K`
    → `FareServiceImpl.searchStopsAfter()` (destination dropdown)
  - `GET /routes/{routeId}/fare?origin=X&destination=Y&adults=2&children=1&infants=1`
    → `FareServiceImpl.calculateFare()` (fare preview before issuing)
  - Verify: `./mvnw compile clean`, app starts clean

### Seed Data

- [ ] S3-29 — Create `DataSeeder.java` in `config/` — `@Component`,
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
  - Verify: app starts, pgAdmin shows route + 29 route_stop rows +
    1 bus + 1 conductor

### Testing & Verification

- [ ] S3-30 — Unit tests: `FareServiceImplTest.java` in `src/test/`:
  - Mock: `RouteStopRepository`, `RouteRepository`
  - `calculateFare_sameStage` — origin=dest stage → stagesCrossed=1, minimum fare
  - `calculateFare_multipleStages` — HSR(5) → KR Puram(10) →
    stagesCrossed=6, adultFare=36, childFare=18, totalFare=90
    (2 adults + 1 child + 1 infant)
  - `calculateFare_destinationBeforeOrigin` → ValidationException thrown
  - `calculateFare_invalidStop` → ResourceNotFoundException thrown
  - `searchStopsAfter_returnsOnlyForwardStops` — stops before origin excluded
  - Verify: `./mvnw test -Dtest=FareServiceImplTest` — all pass

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

- [ ] S3-32 — Commit and merge `feature/route-fare-conductor-auth` into `dev`

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

- [ ] `route` table has new columns: `route_number`, `fare_per_stage`,
      `total_stops`, `origin_stop`, `destination_stop`, `status`
- [ ] `route_stop` table exists with 29 rows for Route 500K visible in pgAdmin
- [ ] `ticket` table has new columns: `stages_crossed`, `adult_count`,
      `child_count`, `infant_count`, `adult_fare`, `child_fare`, `total_fare`;
      old `fare` column manually dropped
- [ ] `POST /conductor/auth/login` returns tokens with `routeId` in response
- [ ] `GET /routes/{routeId}/stops` returns 29 stops in sequence order
- [ ] `GET /routes/{routeId}/stops?search=H` returns only H-matching stops
- [ ] `GET /routes/{routeId}/stops?after=HSR Layout&search=K` returns only
      forward stops matching K
- [ ] `GET /routes/{routeId}/fare` with HSR→KR Puram, 2A+1C+1I returns
      stagesCrossed=6, totalFare=90.00
- [ ] ROLE_PASSENGER cannot access `/routes/**` — returns 403
- [ ] No token on `/admin/**` — returns 401
- [ ] All 5 `FareServiceImplTest` unit tests pass
- [ ] All 8 Postman verification calls pass
- [ ] `feature/route-fare-conductor-auth` merged into `dev`, build clean
