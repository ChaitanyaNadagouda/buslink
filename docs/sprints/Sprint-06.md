# Sprint 6

## Goal

Unlock the admin layer (ROLE_ADMIN JWT + seeded admin account), add 4 analytics
endpoints backed by simple aggregate queries, and introduce Redis caching for
route stops and fare calculation. By end of sprint an admin can login, access
all existing /admin/** endpoints, view system analytics, and conductor dropdown
queries are served from Redis cache instead of hitting the DB every time.

---

## Scope

- Admin entity + `AdminPrincipal` + `AdminDetailsServiceImpl` — same pattern
  as Conductor auth (Sprint 3)
- Admin seeded in `DataSeeder` — `admin@buslink.com` / `Admin@1234`
- `POST /admin/auth/login` — returns ROLE_ADMIN JWT
- All existing `/admin/**` endpoints (Sprint 3) now reachable with ROLE_ADMIN token
- 4 analytics endpoints under `/admin/analytics` (ROLE_ADMIN only):
  - `GET /admin/analytics/revenue-by-route`
  - `GET /admin/analytics/tickets-per-day`
  - `GET /admin/analytics/top-routes`
  - `GET /admin/analytics/conductor-activity`
- Redis via Docker Compose — `spring-boot-starter-data-redis` + `RedisConfig`
- `@Cacheable` on `FareServiceImpl.getStopsForRoute()` and
  `FareServiceImpl.calculateFare()`
- `@CacheEvict` on `RouteServiceImpl.addStop()` and `RouteServiceImpl.updateRoute()`
- `RedisConfig` — `RedisCacheManager` with TTL + JSON serialization
- Unit tests — `AnalyticsServiceImplTest`
- Cache verification — Redis CLI confirms cache hits/misses

**Out of scope:** Passenger management (skipped — low value), WebSocket (skipped),
Offline sync (Sprint 7), Docker app containerization (Sprint 8),
Flyway (deferred), API Gateway (dropped — single monolith, not microservices).

---

## Pre-sprint notes

**Admin entity decision:** BusLink already has `User` (passengers) and `Conductor`
as separate entities. Admin follows the same pattern — a separate `Admin` entity
in `entity/` rather than reusing `User` with a role flag. Reasons:
- Admin has no wallet, no QR token, no deviceId — completely different field set
- Keeps entity boundaries clean — same reasoning as Conductor vs User
- Consistent with the existing two-entity auth pattern already in the codebase

**Analytics query approach:** Pure JPQL aggregate queries in repository layer
(`@Query` annotations on `TicketRepository`) — no new entities, no separate
analytics DB. Data already exists in `ticket` table from Sprints 4–5.

**Redis in Docker Compose:** Redis added to the project's Docker Compose file
alongside the existing PostgreSQL + pgAdmin services (see "Pre-existing gaps
found during plan review" below for the corrected file path). No separate
Redis installation needed — consistent with the project's Docker-first
infrastructure approach.

---

## Pre-existing gaps found during plan review (2026-09-02)

Checked this plan against the actual current codebase before starting —
two real gaps found and resolved:

- **`DataSeeder`'s admin seed would never run on any existing dev database.**
  The plan's S6-14 said to add the admin seed "guarded by
  `adminRepository.count() == 0`, placed after existing conductor seed" —
  but the real, current `DataSeeder.run()` structure is:
  ```java
  public void run(ApplicationArguments args) {
      if (routeRepository.count() > 0) {
          return;                     // early return for the WHOLE method
      }
      // ... seeds route, stops, bus, conductor
  }
  ```
  Placing the new admin block after this early return means it would sit
  *after* a `return` statement that fires on every dev machine that already
  has seeded route data — which is every environment past Sprint 1,
  including the current one. `admin` table would stay empty forever, and
  `POST /admin/auth/login` would never find a seeded account, even though
  the plan's own Definition of Done expects it to work. Same class of bug
  as Sprint 4's check-constraint gap: compiles fine, would even pass if
  tested against a brand-new empty database, but silently breaks on every
  real dev environment.
  **Decision (confirmed 2026-09-02):** restructure the top-level guard from
  an early-return into an `if` block scoped to just the route/stop/bus/
  conductor seed, and add the admin seed as a second, independent
  `if (adminRepository.count() == 0)` block:
  ```java
  public void run(ApplicationArguments args) {
      if (routeRepository.count() == 0) {
          // existing route/stop/bus/conductor seeding
      }
      if (adminRepository.count() == 0) {
          // new admin seeding
      }
  }
  ```
  S6-14's task text below is updated to reflect this corrected structure.

- **Wrong Docker Compose file path.** The plan's S6-20 said "Add Redis to
  `infrastructure/docker-compose.yml`" — that file doesn't exist. The
  project's actual compose file lives at the **project root**
  (`docker-compose.yml`), already defining `postgres`/`pgadmin` on a
  `buslink_network` bridge network.
  **Decision:** add the `redis` service to the real root-level
  `docker-compose.yml`, on the same `buslink_network` for consistency with
  the existing services (host access via the published `6379:6379` port
  works either way, but matching the established pattern is worth it).
  S6-20's task text below is updated to reflect the corrected path.

**Two lower-severity items flagged for live verification, not blocking
plan changes** (same "prove it against the real DB, don't just trust the
JPQL parses" habit as Sprint 4's dotted-config-key and check-constraint
gotchas):
- S6-15's `WHERE t.status = 'PAID'` compares a plain string literal against
  `Ticket.status` (`@Enumerated(EnumType.STRING)`). This generally works in
  Hibernate 6/JPQL, but should be verified to actually return correct rows
  against live Postgres data at S6-15's verify step, not just that the app
  boots and the JPQL parses.
  **Resolved (2026-09-04):** verified live against 6 real tickets (3 `PAID`,
  2 `ISSUED`, 1 `TERMINATED`, all route 500K, ₹90 each) —
  `revenue-by-route` returned exactly `₹270.00` (3×90, the `PAID` ones
  only), confirming the string-literal comparison correctly excludes
  non-`PAID` rows rather than silently matching everything or nothing.
- S6-15's `CAST(t.issuedAt AS date)` — `issuedAt` is stored as `Instant`
  (UTC); casting straight to `date` could shift the calendar day depending
  on Postgres session timezone vs. when a ticket was actually issued
  locally. Worth a live spot-check with a ticket issued near midnight IST.
  **Resolved (2026-09-04):** verified live against the same 6 tickets —
  `tickets-per-day` returned `2026-07-30 → 5` and `2026-08-23 → 1`,
  matching the actual calendar dates the tickets were issued on with no
  timezone shift observed. `AnalyticsServiceImpl.toLocalDate()` was written
  defensively to accept either `LocalDate` or `java.sql.Date` from the
  Object[] projection, since which one Hibernate returns for a `CAST(...
  AS date)` projection wasn't assumed ahead of verifying it.

**Pre-existing gap found during S6-08 live verification (2026-09-02),
deferred — out of this sprint's scope:** a spot-check of `POST
/admin/auth/login` (before S6-13's controller existed) returned `500`
instead of a plain `404`. Root cause: Spring Framework 6.1+ throws
`NoResourceFoundException` for any request matching no controller, and
`GlobalExceptionHandler` has no dedicated handler for it — the generic
`@ExceptionHandler(Exception.class)` catch-all claims it instead, same
failure shape as Sprint 5's `MissingRequestHeaderException` 500→400 bug (a
specific framework exception with no dedicated handler falls into the
generic bucket). Confirmed pre-existing, not caused by this sprint's
changes — it just took a permitAll-rule-with-no-controller-yet combination
to expose it, which hadn't happened before. Once S6-13 adds the real
`AdminAuthController`, this specific path stops triggering it, but the same
gap could still mask any other genuinely-mistyped URL anywhere in the app
as a `500`. **Decision (2026-09-02):** defer — noted here rather than fixed,
since it's unrelated to Sprint 6's admin/analytics/Redis scope. Candidate
fix for a future sprint: add `@ExceptionHandler(NoResourceFoundException.class)`
→ `404` to `GlobalExceptionHandler`, same shape as the Sprint 5 fix.

---

## Deviations from the plan during implementation & verification (2026-09-06)

Four departures from the plan text, all in the Redis caching layer. The first
two are design corrections (the plan's literal code would have been wrong or
non-functional); the last two are cleanup items noted, not yet actioned.

### 1. Fare caching is split — `getFareRate` + `FareRateDTO`, not `@Cacheable` on `calculateFare`

**Plan (S6-24):** put `@Cacheable(value = "fare-calc", key = "#routeId + '-' + #originStop + '-' + #destinationStop")` directly on `calculateFare(routeId, origin, dest, adults, children, infants)`.

**Problem:** `calculateFare`'s return value (`FareResponseDTO`, which includes
`totalFare`) depends on `adults`/`children`/`infants`, but those are
deliberately excluded from the cache key. So the first caller's passenger
counts would be baked into a cached `totalFare` served to every subsequent
caller with different counts. The plan's own note ("Service computes total from
the cached per-stage fare") describes the intended behaviour, but the literal
`@Cacheable`-on-`calculateFare` code doesn't produce it.

**Also:** `@Cacheable` is proxy-based (Spring AOP). A call from inside the same
bean (`this.someMethod()`) bypasses the proxy, so annotating a method that's
only ever called internally would cache nothing.

**What was built:**
- New `FareRateDTO` (`originStop`, `destinationStop`, `stagesCrossed`,
  `adultFare`, `childFare`, `infantFare`) — the per-stage rate, with **no**
  passenger-count-dependent `totalFare` field.
- New `FareService.getFareRate(routeId, origin, dest)` carries the
  `@Cacheable("fare-calc")` annotation and returns `FareRateDTO`.
- `calculateFare` stays uncached, calls `self.getFareRate(...)` where `self` is
  the `FareService` proxy injected back into `FareServiceImpl` via a
  `@Lazy` constructor parameter (breaks the self-referential bean cycle), then
  multiplies the cached rate by the passenger counts to build `FareResponseDTO`.
- `FareServiceImplTest` reworked: `@InjectMocks` replaced with an explicit
  `@BeforeEach` `new FareServiceImpl(routeRepo, routeStopRepo, null)` plus
  `ReflectionTestUtils.setField(fareService, "self", fareService)` so the unit
  test (no Spring proxy) still resolves the internal call to the same instance.

Verified live: the `fare-calc` Redis value is a `FareRateDTO` JSON with no
`totalFare`; requesting the same route/stops with `adults=2` then `adults=5`
produces one cache key and a correctly recomputed total each time.

### 2. `RedisConfig` uses per-cache `JacksonJsonRedisSerializer` with explicit `JavaType`, not `GenericJackson2JsonRedisSerializer`

**Plan (S6-23):** one `GenericJackson2JsonRedisSerializer` for all cache values.

**Reason:** BusLink is on Spring Boot 4.1 / Jackson 3 (`tools.jackson.*`
packages). The cached `route-stops` value is a `List<RouteStopResponseDTO>`;
Java erases the generic, so on read-back a generic serializer yields
`List<LinkedHashMap>` rather than typed DTOs. `RedisConfig` now builds a
`RedisCacheManager` with `withInitialCacheConfigurations(...)`:
- `"route-stops"` → `JacksonJsonRedisSerializer` constructed with a
  `JavaType` for `List<RouteStopResponseDTO>` (via `TypeFactory
  .constructCollectionType`).
- `"fare-calc"` → `JacksonJsonRedisSerializer<>(FareRateDTO.class)`.

Verified live: both cache values round-trip as readable, correctly-typed JSON;
the 2nd `GET /routes/{id}/stops` returns all 29 stops in order from the cache
with no DB query.

### 3. `spring.cache.redis.time-to-live` in `application.properties` is dead config

S6-22 added `spring.cache.redis.time-to-live=3600000` **and** S6-23 hardcodes
`.entryTtl(Duration.ofHours(1))` in the custom `RedisCacheManager` bean.
Because a custom `RedisCacheManager` bean is defined, Spring Boot's
`spring.cache.redis.*` auto-config properties are **ignored entirely** — the
bean's `entryTtl` is the only thing that takes effect. The property is
misleading (change it, restart, nothing happens).
**Not yet actioned.** Candidate fix: bind the property into the bean
(`@Value("${spring.cache.redis.time-to-live}") Duration ttl`) so config lives
in config, or delete the property and keep TTL in the bean. Left for a future
cleanup pass — not worth a code change during sprint closure.

### 4. `spring-boot-starter-data-redis` auto-enabled Redis *repository* scanning

On startup the log shows ~12 lines of `Spring Data Redis - Could not safely
identify store assignment for repository candidate interface
com.buslink.repository.*Repository` — Spring Data is trying to treat every JPA
repository as a Redis repository, then giving up (`Found 0 Redis repository
interfaces`). Harmless (JPA wins), but noise.
**Not yet actioned.** Candidate fix: `spring.data.redis.repositories.enabled=false`
in `application.properties` (we use Redis only as a cache, never as a
`@RedisHash` repository store).

---

## Tasks

### Admin Entity + Enum

- [x] S6-01 — Create `Admin.java` in `entity/` — new entity:
  - `adminId` (UUID, PK, generated)
  - `name` (VARCHAR, not null)
  - `email` (VARCHAR, unique, not null)
  - `passwordHash` (VARCHAR, not null)
  - `status` (AdminStatus enum, not null)
  - Extends `BaseEntity`
  - Table: `admin`
  - Verify: app starts, `admin` table created in pgAdmin

- [x] S6-02 — Create `AdminStatus.java` in `enums/` — ACTIVE, INACTIVE, SUSPENDED
  - Same values as `UserStatus` and `ConductorStatus` — deliberately separate
    enum type (same reasoning as ConductorStatus in Sprint 3: admin lifecycle
    shouldn't be coupled to passenger or conductor lifecycle types)
  - Verify: `./mvnw compile clean` — BUILD SUCCESS

### Admin Repository

- [x] S6-03 — Create `AdminRepository.java` in `repository/` — extend
  `JpaRepository<Admin, UUID>`:
  - `Optional<Admin> findByEmail(String email)`
  - Verify: `./mvnw compile clean`, app boots clean (derived query parsed)

### Admin Security

- [x] S6-04 — Create `AdminPrincipal.java` in `security/` — implements
  `UserDetails`, wraps `Admin`:
  - `getAuthorities()` → `ROLE_ADMIN`
  - `getUsername()` → admin email
  - `isEnabled()` → `AdminStatus.ACTIVE`
  - Exact same pattern as `UserPrincipal` (Sprint 2) and
    `ConductorPrincipal` (Sprint 3)

- [x] S6-05 — Create `AdminDetailsServiceImpl.java` in `security/` —
  implements `UserDetailsService`:
  - `loadUserByUsername(String email)` → load from `AdminRepository`
  - Returns `AdminPrincipal`
  - Throws `UsernameNotFoundException` if not found (same user-enumeration
    prevention pattern as Sprint 2/3)

- [x] S6-06 — Update `JwtUtil.java` in `security/`:
  - Add `generateAdminAccessToken(Admin admin)` and
    `generateAdminRefreshToken(Admin admin)`
  - Role claim = "ADMIN" (consistent with "PASSENGER"/"CONDUCTOR" from Sprint 3)
  - Add `extractRole` already exists from Sprint 3 — no change needed there
  - Update `JwtUtilTest` — add admin token role claim round-trip test
  - Verify: `./mvnw test -Dtest=JwtUtilTest` — all pass

- [x] S6-07 — Update `JwtAuthenticationFilter.java` in `security/`:
  - Current logic is a two-way ternary (`ROLE_CONDUCTOR.equals(role) ?
    conductorDetailsServiceImpl : userDetailsServiceImpl`) — replace with a
    three-way if/else-if/else: PASSENGER → `UserDetailsServiceImpl`,
    CONDUCTOR → `ConductorDetailsServiceImpl` (Sprint 3), ADMIN →
    `AdminDetailsServiceImpl` (new)
  - Inject `AdminDetailsServiceImpl` as concrete class (same pattern as
    Sprint 3's two-class injection — avoids `NoUniqueBeanDefinitionException`)
  - Verify: `./mvnw compile clean`, app boots clean, no bean wiring errors

- [x] S6-08 — Update `SecurityConfig.java`:
  - Add: `POST /admin/auth/login` → permit all (admin login endpoint)
  - `/admin/**` → `hasRole("ADMIN")` already exists from Sprint 3 but was
    unreachable — now reachable once admin JWT is issuable
  - `/admin/auth/login` must be declared BEFORE `/admin/**` rule
    (same ordering rule as `/conductor/auth/login` before `/conductor/**`)
  - Verify: `./mvnw compile clean`, app boots clean

### Admin Auth Service + Controller

- [x] S6-09 — Create `AdminService.java` interface in `service/`

- [x] S6-10 — Create `AdminServiceImpl.java` in `service/impl/`:
  - `login(AdminLoginRequestDTO)`:
    1. Load admin by email → throw `ValidationException("Invalid email or password")`
       if not found (same user-enumeration prevention as Sprints 2/3)
    2. Verify BCrypt password match → same generic error if wrong
    3. Check `AdminStatus.ACTIVE` → throw `ValidationException` if not
    4. Generate tokens via `JwtUtil.generateAdminAccessToken()`
    5. Return `AdminAuthResponseDTO`
  - Verify: `./mvnw compile clean`

- [x] S6-11 — Create `AdminLoginRequestDTO.java` in `dto/request/` (Java record):
  - `email` (@NotBlank, @Email), `password` (@NotBlank)

- [x] S6-12 — Create `AdminAuthResponseDTO.java` in `dto/response/` (Java record):
  - `accessToken`, `refreshToken`, `adminId` (UUID), `name`, `email`

- [x] S6-13 — Create `AdminAuthController.java` in `controller/`:
  - `POST /admin/auth/login` → `AdminServiceImpl.login()`
    → `ApiResponse<AdminAuthResponseDTO>` (permit all)
  - Verify: `./mvnw compile clean`, app boots clean
  - Spot check: `POST /admin/auth/login` (no token) → should not return 401
    (it's permit-all), should return 400 if body missing

### DataSeeder — Admin Account

- [x] S6-14 — Update `DataSeeder.java` in `config/` — **corrected structure,
  see "Pre-existing gaps found during plan review" above:**
  - Restructure the existing top-level `if (routeRepository.count() > 0)
    return;` into `if (routeRepository.count() == 0) { ...existing seed... }`
    so it no longer early-returns the whole method
  - Add a second, independent block: `if (adminRepository.count() == 0) {
    ...new admin seed... }`, placed after the route/stop/bus/conductor
    block — order matters only for readability, no FK dependency between
    admin and other seeded entities
    ```
    Admin:
      name          = "BusLink Admin"
      email         = "admin@buslink.com"
      passwordHash  = BCrypt("Admin@1234")
      status        = ACTIVE
    ```
  - Verify: app restarts, `admin` table has 1 row visible in pgAdmin —
    **on this project's actual current dev DB (route already seeded from
    Sprint 1), not just a fresh empty DB**, since that's exactly the case
    the original guard structure would have missed
  - Verify idempotency: restart again → still 1 row (count check works)

### Analytics — Repository Queries

- [x] S6-15 — Add analytics queries to `TicketRepository.java` in `repository/`
  using `@Query` JPQL:

  ```java
  // Revenue per route — sum of totalFare on PAID tickets, grouped by routeId
  @Query("SELECT t.routeId, SUM(t.totalFare) FROM Ticket t " +
         "WHERE t.status = 'PAID' GROUP BY t.routeId " +
         "ORDER BY SUM(t.totalFare) DESC")
  List<Object[]> findRevenueByRoute();

  // Tickets issued per day — count grouped by date part of issuedAt
  @Query("SELECT CAST(t.issuedAt AS date), COUNT(t) FROM Ticket t " +
         "GROUP BY CAST(t.issuedAt AS date) " +
         "ORDER BY CAST(t.issuedAt AS date) DESC")
  List<Object[]> findTicketsPerDay();

  // Top routes by ticket volume — count of all tickets per route
  @Query("SELECT t.routeId, COUNT(t) FROM Ticket t " +
         "GROUP BY t.routeId ORDER BY COUNT(t) DESC")
  List<Object[]> findTopRoutesByVolume();

  // Conductor activity — tickets issued per conductor
  @Query("SELECT t.conductorId, COUNT(t) FROM Ticket t " +
         "GROUP BY t.conductorId ORDER BY COUNT(t) DESC")
  List<Object[]> findTicketsPerConductor();
  ```

  Note: `Object[]` projections chosen over custom result interfaces for
  simplicity — each analytics query returns exactly 2 columns (id + metric),
  so `Object[]` is less ceremony than defining 4 separate projection interfaces.
  Service layer maps them to typed response DTOs.
  Verify: `./mvnw compile clean`, app boots clean (JPQL parsed at startup),
  **plus a live check against real seeded/test data that `t.status = 'PAID'`
  and both `CAST(t.issuedAt AS date)` usages return correct rows/dates — see
  "Pre-existing gaps found during plan review" above for why this needs a
  live check, not just a successful boot**

### Analytics — DTOs

- [x] S6-16 — Create analytics response DTOs in `dto/response/` (Java records):
  - `RevenueByRouteDTO` — routeId (UUID), routeName (String), totalRevenue (BigDecimal)
  - `TicketsPerDayDTO` — date (LocalDate), ticketCount (Long)
  - `TopRouteDTO` — routeId (UUID), routeName (String), ticketCount (Long)
  - `ConductorActivityDTO` — conductorId (UUID), conductorName (String),
    ticketsIssued (Long)
  - Note: routeName and conductorName require enrichment joins in service layer
    (look up Route/Conductor by id from the query result) — kept out of the
    JPQL query to avoid complexity; N+1 is acceptable for analytics endpoints
    that are called infrequently by a single admin, not in a hot path
  - Verify: `./mvnw compile clean`

### Analytics — Service + Controller

- [x] S6-17 — Create `AnalyticsService.java` interface in `service/`

- [x] S6-18 — Create `AnalyticsServiceImpl.java` in `service/impl/`:
  - `getRevenueByRoute()`:
    → `ticketRepository.findRevenueByRoute()`
    → for each row: look up `Route` by routeId → map to `RevenueByRouteDTO`
    → return `List<RevenueByRouteDTO>`
  - `getTicketsPerDay()`:
    → `ticketRepository.findTicketsPerDay()`
    → map each `Object[]` row to `TicketsPerDayDTO`
    → return `List<TicketsPerDayDTO>`
  - `getTopRoutes()`:
    → `ticketRepository.findTopRoutesByVolume()`
    → for each row: look up `Route` by routeId → map to `TopRouteDTO`
    → return `List<TopRouteDTO>`
  - `getConductorActivity()`:
    → `ticketRepository.findTicketsPerConductor()`
    → for each row: look up `Conductor` by conductorId
    → map to `ConductorActivityDTO`
    → return `List<ConductorActivityDTO>`
  - Verify: `./mvnw compile clean`

- [x] S6-19 — Create `AnalyticsController.java` in `controller/`
  (ROLE_ADMIN only — all endpoints under `/admin/analytics`):
  - `GET /admin/analytics/revenue-by-route`
    → `ApiResponse<List<RevenueByRouteDTO>>`
  - `GET /admin/analytics/tickets-per-day`
    → `ApiResponse<List<TicketsPerDayDTO>>`
  - `GET /admin/analytics/top-routes`
    → `ApiResponse<List<TopRouteDTO>>`
  - `GET /admin/analytics/conductor-activity`
    → `ApiResponse<List<ConductorActivityDTO>>`
  - All endpoints use `@AuthenticationPrincipal AdminPrincipal` (not used
    in method body yet, but good practice — admin identity available if
    needed for audit logging later)
  - Verify: `./mvnw compile clean`, app boots clean

### Redis Setup

- [x] S6-20 — Add Redis to the project's **root-level** `docker-compose.yml`
  (corrected path — see "Pre-existing gaps found during plan review" above;
  the plan originally said `infrastructure/docker-compose.yml`, which
  doesn't exist):
  ```yaml
  redis:
    image: redis:7-alpine
    container_name: buslink-redis
    ports:
      - "6379:6379"
    networks:
      - buslink_network
    restart: unless-stopped
  ```
  Added to the same `buslink_network` as the existing `postgres`/`pgadmin`
  services, for consistency.
  Verify: `docker-compose up -d redis` → container starts, `redis-cli ping`
  returns PONG

- [x] S6-21 — Add Redis dependency to `pom.xml`:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>
  ```

- [x] S6-22 — Add Redis config to `application.properties`:
  ```
  spring.data.redis.host=localhost
  spring.data.redis.port=6379
  spring.cache.type=redis
  spring.cache.redis.time-to-live=3600000
  ```
  TTL = 1 hour (3600000ms) — route stops don't change frequently;
  `@CacheEvict` handles explicit invalidation when stops DO change

- [x] S6-23 — Create `RedisConfig.java` in `config/`:
  ```java
  @Configuration
  @EnableCaching
  public class RedisConfig {

      @Bean
      public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
          RedisCacheConfiguration config = RedisCacheConfiguration
              .defaultCacheConfig()
              .entryTtl(Duration.ofHours(1))
              .serializeValuesWith(
                  RedisSerializationContext.SerializationPair.fromSerializer(
                      new GenericJackson2JsonRedisSerializer()
                  )
              );
          return RedisCacheManager.builder(factory)
              .cacheDefaults(config)
              .build();
      }
  }
  ```
  `GenericJackson2JsonRedisSerializer` — stores cache values as JSON (human-readable
  in Redis CLI, avoids Java serialization version issues)
  Note: Since DTOs are Java records (not Lombok classes), verify Jackson can
  serialize/deserialize them — records with a canonical constructor are
  supported by Jackson 2.12+ (Jackson 3 on Boot 4.1.0 handles them natively).
  Confirmed ahead of time: `RouteStopResponseDTO` and `FareResponseDTO` (the
  two DTOs this sprint caches) are both already plain records with no custom
  logic, so no serialization surprises expected — still verify live per the
  note below.
  Verify: `./mvnw compile clean`, app boots with Redis container running

### Redis Caching — Apply to FareService

- [x] S6-24 — Update `FareServiceImpl.java` in `service/impl/` — add caching:

  ```java
  @Cacheable(value = "route-stops", key = "#routeId")
  public List<RouteStopResponseDTO> getStopsForRoute(UUID routeId) { ... }
  ```
  Cache key = routeId → full stop list cached per route
  Cache name: `"route-stops"`

  ```java
  @Cacheable(value = "fare-calc",
             key = "#routeId + '-' + #originStop + '-' + #destinationStop")
  public FareResponseDTO calculateFare(UUID routeId, String originStop,
      String destinationStop, int adults, int children, int infants) { ... }
  ```
  Cache key = routeId + originStop + destinationStop
  Note: `adults`/`children`/`infants` deliberately excluded from key —
  fare per stage is the same regardless of passenger count; only the
  total is different. Service computes total from the cached per-stage
  fare. This maximizes cache reuse (HSR→KR Puram cached once,
  serves all passenger-count combinations from that single entry).
  Cache name: `"fare-calc"`

  Verify: `./mvnw compile clean`

- [x] S6-25 — Update `RouteServiceImpl.java` in `service/impl/` — add cache eviction:

  ```java
  @CacheEvict(value = "route-stops", key = "#routeId")
  public RouteStopResponseDTO addStop(UUID routeId, CreateRouteStopDTO request) { ... }
  ```

  ```java
  @CacheEvict(value = {"route-stops", "fare-calc"}, allEntries = true)
  public RouteResponseDTO updateRoute(UUID routeId, UpdateRouteRequestDTO request) { ... }
  ```
  `updateRoute` evicts all entries from both caches (farePerStage change
  invalidates all fare calculations; safer to clear all than to try
  key-by-key eviction for a rare admin operation)

  Verify: `./mvnw compile clean`

### Testing & Verification

- [x] S6-26 — Unit tests: `AnalyticsServiceImplTest.java` in `src/test/`:
  - Mock: `TicketRepository`, `RouteRepository`, `ConductorRepository`
  - `getRevenueByRoute_returnsCorrectTotals` — mock query returns
    `[routeId, 450.00]` → verify mapped to `RevenueByRouteDTO` with
    correct routeName (from mocked RouteRepository lookup)
  - `getTicketsPerDay_returnsDescendingDates` — mock returns 3 days
    → verify ordered newest first, counts correct
  - `getTopRoutes_orderedByVolume` — mock returns 2 routes →
    verify higher-volume route appears first
  - `getConductorActivity_returnsCorrectNames` — mock returns conductorId
    → verify enriched with conductorName from ConductorRepository lookup
  - `getRevenueByRoute_emptyResult` — no PAID tickets → returns empty list,
    no exception
  - Verify: `./mvnw test -Dtest=AnalyticsServiceImplTest` — 5/5 pass

- [x] S6-27 — Postman verification sequence:

  **Admin auth:**
  1. `POST /admin/auth/login`
     `{ email: "admin@buslink.com", password: "Admin@1234" }`
     → expect 200, accessToken returned, store as `{{adminToken}}`
  2. `POST /admin/auth/login` wrong password
     → expect 400 "Invalid email or password"
  3. `GET /admin/routes` (adminToken)
     → expect 200, routes list returned (existing Sprint 3 endpoint now reachable)
  4. `GET /admin/routes` (passengerToken)
     → expect 403 (ROLE_PASSENGER cannot access /admin/**)
  5. `GET /admin/routes` (no token)
     → expect 401

  **Analytics:**
  6. `GET /admin/analytics/revenue-by-route` (adminToken)
     → expect 200, list (may be empty if no PAID tickets yet — seed some first)
  7. Issue + pay a ticket via Postman (reuse Sprint 4/5 flow)
     to generate analytics data
  8. `GET /admin/analytics/revenue-by-route` (adminToken)
     → expect route 500K with totalRevenue = ticket's totalFare
  9. `GET /admin/analytics/tickets-per-day` (adminToken)
     → expect today's date with count >= 1
  10. `GET /admin/analytics/top-routes` (adminToken)
      → expect route 500K listed
  11. `GET /admin/analytics/conductor-activity` (adminToken)
      → expect seeded conductor listed with ticketsIssued >= 1
  12. `GET /admin/analytics/revenue-by-route` (conductorToken)
      → expect 403 (CONDUCTOR cannot access admin analytics)

  **Redis cache verification:**
  13. `GET /routes/{routeId}/stops` (conductorToken) — first call
      → expect 200, 29 stops returned
      → check Redis CLI: `redis-cli KEYS "*route-stops*"` → key exists
  14. `GET /routes/{routeId}/stops` (conductorToken) — second call
      → expect 200, same result
      → check Redis CLI: `redis-cli TTL "route-stops::..."` → TTL countdown active
  15. `GET /routes/{routeId}/fare?origin=HSR Layout
      &destination=KR Puram Railway Station&adults=2&children=1&infants=1`
      (conductorToken) — first call
      → expect 200, totalFare=90.00
      → check Redis CLI: `redis-cli KEYS "*fare-calc*"` → key exists
  16. Same fare request — second call
      → expect same result, served from cache
  17. `POST /admin/routes/{routeId}/stops` (adminToken) — add a new stop
      → expect 200
      → check Redis CLI: `redis-cli KEYS "*route-stops*"` → key GONE (evicted)
  18. `GET /routes/{routeId}/stops` (conductorToken) — after eviction
      → expect 200, 30 stops (cache miss → DB hit → recached)
      → check Redis CLI: key exists again (recached on first post-eviction call)
  - Add Admin Auth, Analytics folders to Postman collection

### Git

- [ ] S6-28 — Commit and merge `feature/admin-analytics-redis` into `dev`

---

## Dependencies

| Dependency | Status |
|---|---|
| Spring Boot 4.1.0 project | ✅ Done (Sprint 1) |
| Ticket table with totalFare, status, routeId, conductorId, issuedAt | ✅ Done (Sprints 1 & 4) |
| Route, Conductor entities + repositories | ✅ Done (Sprint 3) |
| /admin/** endpoints (routes, buses, conductors) | ✅ Done (Sprint 3) — unreachable until this sprint |
| JWT infrastructure with role claims | ✅ Done (Sprints 2 & 3) |
| ConductorPrincipal, ConductorDetailsServiceImpl pattern | ✅ Done (Sprint 3) — template for Admin |
| GlobalExceptionHandler (ValidationException, etc.) | ✅ Done (Sprint 2) |
| Docker Compose (PostgreSQL + pgAdmin, root-level `docker-compose.yml`) | ✅ Done (Sprint 1) |
| PaymentGatewayPort / RazorpayGatewayAdapter | ✅ Done (Sprint 5) |
| Redis Docker service | ⏳ S6-20 (this sprint) |

---

## Definition of Done

- [x] `admin` table exists with 1 seeded row (admin@buslink.com) — confirmed via
      `psql` on this project's existing dev DB (route already seeded), which is
      exactly the case the original early-return guard would have missed;
      pgAdmin visual check skipped this sprint (deliberate — `psql \d`/`SELECT`
      is authoritative for row presence)
- [x] `POST /admin/auth/login` returns ROLE_ADMIN JWT (role claim `"ADMIN"`,
      round-trip covered by `JwtUtilTest.extractRole_roundTrips_forAdminTokens`)
- [x] `GET /admin/routes` with admin token → 200 (was 403 before this sprint)
- [x] `GET /admin/routes` with passenger token → 403
- [x] `GET /admin/routes` with no token → 401
- [x] All 4 analytics endpoints return data after seeding a PAID ticket —
      revenue-by-route `₹90.00`, tickets-per-day `2026-09-06 → 1`, top-routes
      `1`, conductor-activity `Test Conductor → 1`; route/conductor names all
      enriched (not null)
- [x] Analytics endpoints return 403 for non-admin tokens (conductor token)
- [x] Redis container running (`redis-cli ping` → PONG)
- [x] `GET /routes/{routeId}/stops` — second call served from Redis
      (`route-stops::<routeId>` key present, value readable JSON, TTL counting
      down from ~3600, 2nd call returns all 29 stops with no SQL) — this
      exercises the custom `JacksonJsonRedisSerializer` deserialization
- [x] `GET /routes/{routeId}/fare` — second call served from Redis
      (`fare-calc::<routeId>-HSR Layout-KR Puram Railway Station` key present;
      value is a `FareRateDTO` JSON with **no `totalFare`** — see deviation 1;
      changing `adults` produces no new key and recomputes the total correctly)
- [x] `POST /admin/routes/{routeId}/stops` evicts `route-stops` cache
      (`KEYS *route-stops*` empty after; `fare-calc` correctly untouched — only
      `updateRoute` clears both)
- [x] All 5 `AnalyticsServiceImplTest` tests pass
- [x] Full test suite passes — 71 run, 0 failures, 0 errors (66 pre-existing +
      5 new `AnalyticsServiceImplTest`; `FareServiceImplTest` reworked in place
      for the `self`-proxy wiring, still 5)
- [ ] `feature/admin-analytics-redis` merged into `dev`, build clean
