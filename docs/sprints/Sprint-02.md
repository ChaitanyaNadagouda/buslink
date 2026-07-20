# Sprint 2

## Goal

Implement JWT-based authentication for passengers, repository and service layer carried
over from Sprint 1, QR token generation on registration, and the first secured REST
endpoints. By end of sprint a passenger can register, login, receive a JWT, and fetch
their QR token via an authenticated API call.

---

## Scope

- Carried over from Sprint 1: all repositories and the UserService stub (S1-28/S1-29)
- JWT infrastructure: JwtUtil, JwtAuthenticationFilter, UserDetailsServiceImpl
- SecurityConfig locked down — replaces Sprint 1's permit-all stub
- Passenger auth flow: register, login, token refresh
- QR token generation at registration time
- UserController: GET /user/profile, GET /user/qr
- Input validation on all request DTOs
- Postman collection setup for Auth and User
- Unit tests for AuthService

**Out of *scope:** Conductor auth (Sprint 3), role-based endpoint security beyond
ROLE_PASSENGER (Sprint 3), wallet recharge/payment flows (Sprint 5), Flyway (deferred).

---

## Tasks

### Carried Over from Sprint 1

- [x] S1-28 — `UserRepository.java` in `repository/` — extend `JpaRepository<User, UUID>`;
  add `Optional<User> findByEmail(String email)` and
  `Optional<User> findByQrToken(String qrToken)` — verified: `./mvnw compile` clean
- [x] S1-29 — `WalletRepository.java` in `repository/` — extend
  `JpaRepository<Wallet, UUID>`; add `Optional<Wallet> findByUserId(UUID userId)` —
  verified: `./mvnw compile` clean
- [x] S1-29b — `UserServiceImpl.java` in `service/impl/` — implement `findById(UUID)`,
  `findByEmail(String)` to verify layer wiring before auth is built on top. Added a
  `UserService` interface in `service/` (impl implements it) to match the interface/impl
  package split already scaffolded in S1-06 — services depend on the abstraction, not the
  concrete class. Both methods throw `ResourceNotFoundException` on a miss. Verified:
  `./mvnw compile` clean

### Dependencies & Setup

- [x] S2-01 — Add JWT dependency to `pom.xml`: `jjwt-api`/`jjwt-impl`/`jjwt-jackson`
  pinned to **0.13.0** (current stable at implementation time — the sprint plan's
  `0.12.x` was a placeholder). `jjwt` chosen over Nimbus JOSE+JWT since we're issuing
  our own tokens with our own claims, not consuming external OAuth2/OIDC tokens — jjwt's
  purpose-built sign/parse API fits better than Nimbus's lower-level JOSE machinery.
  Verified: `./mvnw dependency:tree` shows all 3 artifacts resolved with correct scopes
  (api=compile, impl/jackson=runtime); `./mvnw clean compile` clean. (`clean install`
  fails on `BusLinkApplicationTests.contextLoads` — pre-existing, this shell has no
  DB/Docker connectivity, unrelated to this change.)

### JWT Infrastructure

- [x] S2-02 — `JwtUtil.java` in `security/` — methods:
  `generateAccessToken(User)`, `generateRefreshToken(User)`,
  `extractUsername(String token)`, `isTokenValid(String token, UserDetails userDetails)`,
  `extractClaim(String token, Function<Claims, T> claimsResolver)`.
  Read secret key and expiry from `application.properties`
  (`jwt.secret`, `jwt.access-token-expiry-ms`, `jwt.refresh-token-expiry-ms`) via
  constructor-injected `@Value` params (properties themselves land in S2-19).
  HS256, secret Base64-decoded into a `SecretKey` once in the constructor. Added a
  `"type"` claim (`access`/`refresh`, not in the original spec) so a leaked access
  token can't be replayed against the refresh flow — see S2-14. `isTokenValid` catches
  jjwt's parse exceptions internally and returns `false` rather than throwing.
  **Added (not on original task list, approved):** `JwtUtilTest.java` in
  `src/test/java/com/buslink/security/` — constructs `JwtUtil` directly (no Spring
  context, since `jwt.*` properties don't exist until S2-19 and this shell has no
  DB/Docker). 6 tests, all passing: round-trip extraction, valid token, username
  mismatch, expired token, tampered token, `"type"` claim on both token kinds.
  Verified: `./mvnw test -Dtest=JwtUtilTest` — 6/6 pass.

- [x] S2-03 — `UserDetailsServiceImpl.java` in `security/` — implement
  `UserDetailsService.loadUserByUsername(String email)`, load from `UserRepository`,
  throw `UsernameNotFoundException` if not found (not `ResourceNotFoundException` —
  Spring Security's `DaoAuthenticationProvider` specifically catches
  `UsernameNotFoundException` and translates it to a generic bad-credentials failure,
  preventing user-enumeration via distinct error responses). Map `UserStatus` to
  Spring Security's `enabled` flag (ACTIVE=true, others=false) via `isEnabled()`.
  **Added (discussed, approved):** `UserPrincipal.java` in `security/` — small adapter
  implementing `UserDetails`, wrapping `User` (considered having `User` implement
  `UserDetails` directly; went with a separate adapter instead to keep the `entity/`
  package free of Spring Security framework types, consistent with every other entity).
  Grants a fixed `ROLE_PASSENGER` authority (all `User` rows are riders; `Conductor` is
  a separate entity/auth path in Sprint 3). Verified: `./mvnw compile` clean.

- [x] S2-04 — `JwtAuthenticationFilter.java` in `security/` — extend
  `OncePerRequestFilter`; extract Bearer token from `Authorization` header,
  validate via `JwtUtil`, set `UsernamePasswordAuthenticationToken` in
  `SecurityContextHolder` if valid. Skip filter for `/auth/**` paths (via
  `shouldNotFilter`, plain `startsWith` prefix check — no path-matcher library
  needed for one static prefix). Catches `JwtException`/`IllegalArgumentException`/
  `UsernameNotFoundException` around the whole authenticate-attempt and just skips
  authentication on any of them (never rejects the request itself — that's
  `SecurityConfig`'s job in S2-05). Verified: `./mvnw compile` clean, existing
  `JwtUtilTest` still 6/6 pass. **Not yet behaviorally exercised** — this filter
  isn't wired into the filter chain until S2-05 replaces the Sprint 1 permit-all
  stub; real verification comes with S2-05 and the S2-22 Postman sequence.

### Security Config

- [x] S2-05 — Update `SecurityConfig.java` in `security/` — replace permit-all stub:
  - CSRF disabled (stateless JWT API) — unchanged from Sprint 1
  - `SessionCreationPolicy.STATELESS` — unchanged from Sprint 1
  - Permit: `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh` (method-scoped
    via `HttpMethod.POST`), `/swagger-ui/**`, `/v3/api-docs/**`
  - All other requests: `.authenticated()`
  - `JwtAuthenticationFilter` registered via `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)`,
    injected as a `@Bean` method parameter (not a class-level constructor field, since
    `SecurityConfig` itself has no other dependencies to justify one)
  - **Gotcha hit and documented in `ARCHITECTURE.md`:** Spring Security 7 (paired with
    Boot 4.1.0) moved `UsernamePasswordAuthenticationFilter` to
    `org.springframework.security.web.authentication` (was
    `org.springframework.security.authentication` in Boot-3-era Spring Security) —
    caught immediately via `./mvnw compile` failing on the old import.
  - Verified: `./mvnw compile` clean, existing `JwtUtilTest` still 6/6 pass.
  - **Not yet verified: "Swagger UI still returns HTTP 200."** Cannot run the full app
    yet — `JwtUtil`'s `@Value("${jwt.secret}")` has nothing to resolve until S2-19 adds
    the `jwt.*` properties, and this shell has no DB/Docker connectivity regardless.
    Same kind of sequencing gap as Sprint 1's S1-22/S1-26 — will re-verify live once
    S2-19 lands and the app can actually start.

### DTOs

- [x] S2-06 — `UserSignUpRequestDTO.java` in `dto/request/` — Java record;
  fields: `name` (@NotBlank), `email` (@NotBlank, @Email), `mobileNo` (@NotBlank,
  @Pattern E.164 or 10-digit), `password` (@NotBlank, @Size min=8). **Decided:**
  plain 10-digit Indian mobile number (`^[6-9]\d{9}$`), not E.164 — matches how
  numbers are naturally typed for a Bangalore-only service and keeps the format
  simple for a future SMS OTP gateway. Verified: `./mvnw compile` clean.

- [x] S2-07 — `UserLoginRequestDTO.java` in `dto/request/` — Java record;
  fields: `email` (@NotBlank, @Email), `password` (@NotBlank). Verified:
  `./mvnw compile` clean.

- [x] S2-08 — `AuthResponseDTO.java` in `dto/response/` — Java record;
  fields: `accessToken`, `refreshToken`, `userId` (UUID), `email`, `name`.
  Verified: `./mvnw compile` clean.

- [x] S2-09 — `UserProfileResponseDTO.java` in `dto/response/` — Java record;
  fields: `userId`, `name`, `email`, `mobileNo`, `status`, `qrToken`, `createdAt`
  (`Instant`, matching `BaseEntity`). **Discussed:** `userId` kept despite the
  endpoint always meaning "me" — a resource representation includes its own
  identifier, useful as a stable client-side cache key, not a security concern
  since it's already in the JWT claims. Verified: `./mvnw compile` clean.

- [x] S2-10 — `RefreshTokenRequestDTO.java` in `dto/request/` — Java record;
  field: `refreshToken` (@NotBlank). Kept separate from `AuthResponseDTO`'s
  `refreshToken` field — request and response DTOs stay distinct types even on
  field-name overlap, since only the request side carries `@Valid` constraints.
  Verified: `./mvnw compile` clean.

### QR Token

- [x] S2-11 — `QrTokenUtil.java` in `util/` — static method
  `generateQrToken()`: generate a URL-safe UUID-based token (e.g.
  `UUID.randomUUID().toString().replace("-", "")` or Base64-encoded UUID bytes).
  Must be unique — uniqueness enforced by DB constraint already on `users.qr_token`.
  **Decided:** plain UUID hex (`UUID.randomUUID().toString().replace("-", "")`, 32 chars)
  over Base64-encoded UUID bytes — simplest option, no URL-safe-encoding pitfalls, human
  readable in pgAdmin while debugging, consistent with UUIDs used everywhere else in this
  codebase. Plain static utility (private constructor, no `@Component`) — no config/state
  to justify a Spring-managed bean, unlike `JwtUtil`. Verified: `./mvnw compile` clean.

### Auth Service

- [x] S2-12 — `AuthServiceImpl.java` in `service/impl/` — implement `register()`:
  1. Check email uniqueness — throw `ValidationException` if duplicate
     (`DataIntegrityViolationException` from DB already handled in
     `GlobalExceptionHandler` but catch early for a cleaner message)
  2. BCrypt hash the password via `PasswordEncoder`
  3. Generate `qrToken` via `QrTokenUtil.generateQrToken()`
  4. Save `User` with status `ACTIVE`
  5. Create and save `Wallet` (balance=0, status=ACTIVE) linked by `userId`
  6. Generate and return `AuthResponseDTO` with access + refresh tokens

  **Added (discussed, approved):** `AuthService` interface in `service/` (impl
  implements it), matching the interface/impl split from `UserService` (S1-29b).
  `register()` marked `@Transactional` — the `User` save and `Wallet` save are two
  separate writes that must succeed or fail together, otherwise a `Wallet` save
  failure would leave an orphaned `User` with no wallet. `PasswordEncoder` is
  constructor-injected as an interface type even though its `@Bean` doesn't exist
  yet (S2-20, still pending) — same sequencing gap already hit with `JwtUtil`'s
  `@Value` properties in S2-02/S2-19; the type is on the classpath via
  `spring-boot-starter-security` so it compiles, wiring resolves once S2-20 lands.
  Verified: `./mvnw compile` clean.

- [x] S2-13 — `AuthServiceImpl.java` — implement `login()`:
  1. Load user by email — throw `ResourceNotFoundException` if not found
  2. Verify BCrypt password match — throw `ValidationException` if mismatch
  3. Check `UserStatus.ACTIVE` — throw `ValidationException` with appropriate
     message if INACTIVE/SUSPENDED
  4. Generate and return `AuthResponseDTO`

  **Deviated (discussed, approved):** email-not-found and wrong-password both throw
  the same `ValidationException("Invalid email or password")` (400) instead of the
  originally planned `ResourceNotFoundException` (404) for a missing email. As
  scoped, the two cases would have returned distinguishable status codes, letting
  an attacker enumerate registered emails via `/auth/login` — the exact user-
  enumeration risk `UserDetailsServiceImpl` (S2-03) was already written to avoid,
  just not automatically inherited here since this is a hand-rolled flow, not
  routed through Spring Security's `DaoAuthenticationProvider`. Account-inactive
  check unchanged from the plan. Verified: `./mvnw compile` clean.

- [x] S2-14 — `AuthServiceImpl.java` — implement `refreshToken()`:
  1. Extract and validate refresh token via `JwtUtil`
  2. Load user, verify still ACTIVE
  3. Issue new access token only — return `AuthResponseDTO`
     (refresh token unchanged, not rotated in this sprint)

  **Added (discussed, approved):** `JwtUtil.isRefreshToken(String token)` — checks
  the `"type"` claim equals `"refresh"`, catching `JwtException`/
  `IllegalArgumentException` internally and returning `false` (same pattern as
  `isTokenValid`). Kept inside `JwtUtil` rather than exposing `CLAIM_TYPE`/
  `TOKEN_TYPE_REFRESH` to `AuthServiceImpl` — claim-key knowledge stays
  encapsulated. All failure modes (wrong token type, expired/tampered, user not
  found, user inactive) throw the same generic
  `ValidationException("Invalid or expired refresh token")` — same reasoning as
  S2-13's unified login error, no benefit in distinguishing reasons to the caller.
  Verified: `./mvnw compile` clean, `JwtUtilTest` still 6/6 pass.

  **Amendment to S2-04 (discovered while building this, approved):**
  `JwtAuthenticationFilter` validated signature + username + expiry via
  `isTokenValid()`, but never checked token `type` — meaning a leaked refresh
  token (7-day life) could be used directly as a Bearer token against any
  protected endpoint, defeating the short-lived-access-token design. Fixed by
  rejecting the request early (`filterChain.doFilter()` without authenticating)
  whenever `jwtUtil.isRefreshToken(token)` is true, using the same new method.
  Verified: `./mvnw compile` clean, `JwtUtilTest` still 6/6 pass. **Not yet
  behaviorally exercised** — same reasoning as S2-04's original note; real
  verification comes with the S2-22 Postman sequence once the app can start.

### User Service

- [x] S2-15 — `UserServiceImpl.java` — implement `getUserProfile(UUID userId)`:
  load `User` by id, throw `ResourceNotFoundException` if absent,
  map to `UserProfileResponseDTO`.

  **Discussed:** manual inline mapping (`new UserProfileResponseDTO(...)`, reusing
  the existing `findById()`) chosen over introducing MapStruct now — this is a
  single flat 1:1 field copy; MapStruct's build-time codegen is the right tool
  once more entities/DTOs with real mapping complexity exist, not for the first
  one. Revisit when a second/third mapping makes the boilerplate genuinely
  repetitive. Verified: `./mvnw compile` clean.

- [x] S2-16 — `UserServiceImpl.java` — implement `getQrToken(UUID userId)`:
  load user, return `qrToken` field wrapped in `ApiResponse`.

  **Deviated (discussed, approved):** returns a bare `String`, not
  `ApiResponse`-wrapped — matches the S2-15 precedent (`getUserProfile()` also
  returns a bare DTO). `ApiResponse` is an HTTP-response envelope; wrapping it at
  the service layer would leak an HTTP-layer concept into business logic.
  `UserController` (S2-18) does `ApiResponse.success(qrToken)` instead, keeping
  both `UserService` methods consistent. Verified: `./mvnw compile` clean.

### Controllers

- [x] S2-17 — `AuthController.java` in `controller/` — endpoints:
  - `POST /auth/register` → `AuthServiceImpl.register()` → `ApiResponse<AuthResponseDTO>`
  - `POST /auth/login` → `AuthServiceImpl.login()` → `ApiResponse<AuthResponseDTO>`
  - `POST /auth/refresh` → `AuthServiceImpl.refreshToken()` → `ApiResponse<AuthResponseDTO>`
  All request bodies annotated with `@Valid`.

  First controller in the project. All three methods return the bare
  `ApiResponse<AuthResponseDTO>` (no `ResponseEntity` wrapper) — Spring MVC's
  default 200 OK is correct for all three, so no custom status/headers are
  needed. No try/catch anywhere — `@Valid` failures and every service-thrown
  exception (`ValidationException`, etc.) are already handled by
  `GlobalExceptionHandler`; the controller is pure HTTP-shape glue. `register()`
  deliberately returns 200, not 201 Created — its body is an authenticated
  session (`AuthResponseDTO`, tokens), not a representation of the created
  `User` resource, so the usual "201 for resource creation" convention doesn't
  fit here. Verified: `./mvnw compile` clean.

- [x] S2-18 — `UserController.java` in `controller/` — endpoints:
  - `GET /user/profile` → `UserServiceImpl.getUserProfile()` → `ApiResponse<UserProfileResponseDTO>`
    (extract `userId` from `SecurityContextHolder`)
  - `GET /user/qr` → `UserServiceImpl.getQrToken()` → `ApiResponse<String>`
  Both endpoints require authenticated PASSENGER — no explicit role check needed
  yet since all authenticated users are passengers at this stage.

  `userId` extracted via `@AuthenticationPrincipal UserPrincipal principal` (Spring
  Security's `AuthenticationPrincipalArgumentResolver`, resolved from
  `SecurityContextHolder` before the controller method is invoked) rather than
  manual `SecurityContextHolder.getContext()...` calls — avoids repeating that
  boilerplate per endpoint and only works because `UserPrincipal` is the actual
  principal type `JwtAuthenticationFilter` puts in the context (S2-03). No
  `@PreAuthorize`/role check — `SecurityConfig`'s `anyRequest().authenticated()`
  already gates both paths, and every authenticated user is `ROLE_PASSENGER` at
  this stage. Verified: `./mvnw compile` clean.

### Configuration

- [x] S2-19 — Add JWT config to `application.properties`:
  ```
  jwt.secret=<minimum-256-bit-hex-or-base64-secret>
  jwt.access-token-expiry-ms=900000
  jwt.refresh-token-expiry-ms=604800000
  ```
  Add `jwt.secret` to `.gitignore` or use env-var placeholder consistent with
  the existing `infrastructure/.env` pattern.

  Reused the existing `${POSTGRES_USER}`-style pattern rather than inventing a
  new mechanism: `jwt.secret=${JWT_SECRET}` in `application.properties`, real
  value added to `infrastructure/.env` (gitignored, generated via
  `openssl rand -base64 32` — 256-bit minimum enforced at runtime by jjwt's
  `Keys.hmacShaKeyFor`, which throws `WeakKeyException` on a shorter key),
  placeholder documented in `infrastructure/.env.example`, README's existing
  env-loading instructions (shell `source` step and IntelliJ Run Configuration
  note) extended to include `JWT_SECRET`. Expiry values are plain numbers, not
  secrets — added directly, no placeholder needed. Verified: `./mvnw compile`
  clean.

- [x] S2-20 — Register `PasswordEncoder` `@Bean` (BCryptPasswordEncoder) in
  `config/` or `security/` — must be a separate `@Bean` to avoid circular
  dependency with `UserDetailsServiceImpl`.

  Added as a `@Bean` method inside the existing `SecurityConfig` class
  (`security/`) rather than a new file — already the natural home for
  security-related bean wiring. **Note:** the stated "avoid circular dependency
  with `UserDetailsServiceImpl`" rationale is standard Spring Security tutorial
  advice for a `DaoAuthenticationProvider` setup (which needs both
  `UserDetailsService` and `PasswordEncoder` at once) — this project doesn't
  actually wire that provider chain (`AuthServiceImpl.login()` calls
  `passwordEncoder.matches()` directly; `UserDetailsServiceImpl` is only used by
  `JwtAuthenticationFilter`), so that specific cycle doesn't concretely apply
  here. Kept as its own bean regardless, for testability and in case a future
  auth path (OTP login, conductor/admin auth) does use the standard provider
  chain. This closes the last runtime-wiring gap — `AuthServiceImpl` now has
  every dependency it needs to actually resolve at startup. Verified:
  `./mvnw compile` clean.

### Testing & Verification

- [ ] S2-21 — Postman collection — create `BusLink API` collection with folders:
  - `Auth`: POST /auth/register, POST /auth/login, POST /auth/refresh
  - `User`: GET /user/profile, GET /user/qr
  Set collection-level variable `{{baseUrl}} = http://localhost:8080` and
  `{{accessToken}}` (populated automatically from login response via Postman test script).

- [ ] S2-22 — Postman verification sequence:
  1. `POST /auth/register` — new user → expect 200, `AuthResponseDTO` returned,
     `qrToken` non-null in DB (verify in pgAdmin: `SELECT qr_token FROM users`)
  2. `POST /auth/login` — same credentials → expect 200, tokens returned
  3. `GET /user/profile` — with Bearer token → expect 200, profile returned
  4. `GET /user/qr` — with Bearer token → expect 200, qrToken returned
  5. `GET /user/profile` — without token → expect 401
  6. `POST /auth/register` — same email again → expect 409 conflict
  7. `POST /auth/login` — wrong password → expect 400

- [ ] S2-23 — Unit tests in `src/test/`:
  - `AuthServiceImplTest` — mock `UserRepository`, `WalletRepository`,
    `PasswordEncoder`, `JwtUtil`:
    - `register_success` — verify user saved, wallet created, tokens returned
    - `register_duplicateEmail` — verify `ValidationException` thrown
    - `login_success` — verify tokens returned
    - `login_wrongPassword` — verify `ValidationException` thrown
    - `login_inactiveUser` — verify `ValidationException` thrown

### Git

- [ ] S2-24 — Commit and merge `feature/auth` into `dev`

---

## Dependencies

| Dependency | Status |
|---|---|
| Spring Boot 4.1.0 project | ✅ Done (Sprint 1) |
| Flat package structure | ✅ Done (Sprint 1) |
| All 9 entities + enums | ✅ Done (Sprint 1) |
| BaseEntity, GlobalExceptionHandler, ApiResponse | ✅ Done (Sprint 1) |
| SecurityConfig stub (permit-all) | ✅ Done (Sprint 1) — replaced in S2-05 |
| PostgreSQL running via Docker Compose | ✅ Done (Sprint 1) |
| `users` table with `qr_token`, `mobile_no`, `status` columns | ✅ Done (Sprint 1) |
| `wallet` table with `@Version` | ✅ Done (Sprint 1) |
| LLD / DB schema finalized | ⏳ Pending — User/Wallet fields used here are Sprint 1 drafts |

---

## Definition of Done

- [ ] `POST /auth/register` creates a User (status=ACTIVE) and Wallet (balance=0)
      in DB, returns JWT access + refresh tokens and a non-null `qrToken`
- [ ] `POST /auth/login` with correct credentials returns tokens
- [ ] `POST /auth/login` with wrong credentials returns 400 via `ApiResponse`
- [ ] `GET /user/profile` with valid Bearer token returns profile — 401 without token
- [ ] `GET /user/qr` with valid Bearer token returns `qrToken`
- [ ] `POST /auth/register` with duplicate email returns 409 via `ApiResponse`
- [ ] Swagger UI still accessible at `/swagger-ui/index.html` after SecurityConfig update
- [ ] All 5 Postman requests pass
- [ ] All 5 unit tests in `AuthServiceImplTest` pass
- [ ] `feature/auth` merged into `dev`, build clean
