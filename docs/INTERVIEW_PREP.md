# Interview Prep — BusLink

One-stop, high-level last-minute prep notes: the non-obvious engineering decisions made on this project, framed as interview questions, with the *why* — not just the *what*. Each entry links back to where the real reasoning lives (`ARCHITECTURE.md`, the relevant sprint file) if you need the full context.

**Structure:** one `## Sprint N` section per sprint, each broken into the same topic subsections (Database & Persistence, Concurrency & Data Integrity, Lombok & JPA, Security, Domain Modeling, API Design & Error Handling, Offline-First Design, ...). Keep new sprints in this same topic-bucketed shape — add a new topic subsection only if a sprint introduces a genuinely new category, otherwise file new Q&As under the existing ones.

**Keep this updated:** whenever a new non-trivial design decision gets made, add a Q&A under the current sprint's section — same trigger as updating `ARCHITECTURE.md`/`DEVELOPMENT_LOG.md`.

---

## Sprint 1

### Database & Persistence Design

**Q: Why UUID primary keys instead of auto-increment `Long`?**
A: This is an offline-first system — a conductor's device can create a `Ticket` with zero connectivity, so the ID must be generated client-side with no server round-trip. Auto-increment requires the database to be the single authority on "what's next," which breaks the moment two offline devices need to create records independently. UUIDs have no collision risk across devices.
*Trade-off:* 16 bytes vs 8, slightly worse B-tree index locality — acceptable since these aren't hot join keys at scale.

**Q: Why `@MappedSuperclass` (`BaseEntity`) instead of duplicating `createdAt`/`updatedAt` on every entity, or using real entity inheritance?**
A: `@MappedSuperclass` shares columns without sharing a table — exactly the need here (every entity wants audit columns, but they aren't polymorphic variants of one business concept). Real entity inheritance (`SINGLE_TABLE`/`JOINED`/`TABLE_PER_CLASS`) is for when subtypes genuinely are variants of the same concept (e.g. `Vehicle` → `Bus`/`Car`) — using it here would incorrectly imply `User` and `Wallet` are "the same thing."

**Q: `@CreationTimestamp`/`@UpdateTimestamp` vs Spring Data's `@CreatedDate`/`@LastModifiedDate` + `@EnableJpaAuditing` — what's the difference, and which did we use?**
A: Two separate, non-overlapping mechanisms. Hibernate-native (`@CreationTimestamp`) works standalone via Hibernate's own event listeners — no Spring wiring needed. Spring Data JPA auditing (`@EnableJpaAuditing`) is a separate framework, whose real value is `@CreatedBy`/`@LastModifiedBy` (recording *who* changed a row). We used Hibernate-native only, and deliberately skipped `@EnableJpaAuditing` since enabling it does nothing without also using its own annotations — and there's no authenticated "current user" yet to attribute changes to anyway.

**Q: Why are foreign keys (`userId`, `routeId`, etc.) plain UUID columns instead of `@ManyToOne`/`@OneToOne` object relationships?**
A: This sprint has an explicit non-goal: no business logic, nothing navigates the object graph yet. A real relationship brings in lazy-loading semantics, cascade rules, and N+1 query risk — real concerns, but ones that matter once the service layer actually needs `wallet.getUser().getName()`. Deliberately deferred until an actual use case needs it, not built speculatively.

**Q: `BigDecimal` for money — why not `float`/`double`?**
A: `float`/`double` are binary floating point and can't represent many decimal fractions exactly (`0.1 + 0.2 != 0.3` in binary). Unacceptable for money. `BigDecimal` represents decimal digits exactly. Paired with `@Column(precision = 12, scale = 2)` — precision is total significant digits, scale is digits after the decimal point.

**Q: Why does Postgres store JSON as `jsonb`, and how does that map to Java (`SyncEvent.payload`)?**
A: Postgres has two JSON types: `json` (stores exact text, re-parses every query) and `jsonb` (decomposed binary, faster, indexable) — `jsonb` is the standard choice. Hibernate 6+ maps this natively via `@JdbcTypeCode(SqlTypes.JSON)` on a `Map<String, Object>` field — no third-party library needed (older Boot/Hibernate setups needed `hibernate-types` for this).

**Q: Why does `SyncEvent` need both `entityType` *and* `entityId`, when `payload` already contains the full record?**
A: Without `entityId` as its own indexed column, finding "all sync events for ticket X" means querying *into* the JSON blob (`payload->>'ticketId'`) — fragile (depends on JSON key names staying consistent) and not normally indexed. A plain `entityId UUID` column gives a fast, ordinary indexed lookup, independent of whatever shape the JSON payload takes.

### Concurrency & Data Integrity

**Q: What does `@Version` on `Wallet` actually protect against, and how does it work?**
A: Two near-simultaneous requests debiting the same wallet (e.g. a double-tap, or a retry after a network blip). Without protection, both read balance=100, both compute 100-30=70, both write 70 — one debit is silently lost. `@Version` is a `Long` column Hibernate checks on every `UPDATE`; if another transaction already bumped it, this one throws `OptimisticLockException` instead of corrupting the balance.

**Q: Optimistic vs pessimistic locking — why optimistic here?**
A: Pessimistic locking (`SELECT ... FOR UPDATE`) blocks other transactions from even reading until the lock releases — real throughput cost. Optimistic locking only fails at the point of actual conflict, which is rare for wallet updates (mostly reads; concurrent writes to the *same* wallet are the exception). Standard choice for this access pattern.

**Q: How is an `ObjectOptimisticLockingFailureException` actually surfaced to the API caller?**
A: `GlobalExceptionHandler` has a dedicated `@ExceptionHandler` for it, returning `409 Conflict` with a retry-suggesting message — not left to bubble up as a raw 500. Directly connects to why `@Version` was added to `Wallet` in the first place: no point protecting the data if the failure mode isn't handled cleanly at the API boundary.

**Q: Why does `gatewayReferenceId` on `Payment` have a unique constraint?**
A: Idempotency. Payment gateways commonly redeliver the same webhook callback more than once. A unique constraint means a duplicate insert fails fast at the DB level instead of silently double-processing the same payment.

### Lombok & JPA

**Q: Why not just put `@Data` on every entity?**
A: `@Data` generates `equals()`/`hashCode()` from *every* field, including the primary key — wrong for a JPA entity, where identity should be based on the primary key alone. Before persistence, the `UUID id` may still be null, producing inconsistent equality behavior in `Set`/`HashMap` (the object's effective hash code changes after save). It also generates `toString()` over every field, which can trigger unwanted lazy-loading or infinite recursion once bidirectional relationships exist.

**Q: What did we use instead?**
A: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`, plus `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with `@EqualsAndHashCode.Include` on just the ID field. Identity is based solely on the primary key, matching how JPA-managed entities should behave.

**Q: Once entities extend `BaseEntity`, why plain `@Builder` and not `@SuperBuilder`?**
A: Normally `@Builder` on a subclass silently ignores inherited superclass fields — here that's actually correct, not a bug to fix. `createdAt`/`updatedAt` are Hibernate-managed timestamps that should never be set manually at construction time, so excluding them from the builder is a desirable side effect.

**Q: Why is `ApiResponse` a Java `record` instead of a Lombok class?**
A: It's a pure, immutable data carrier with no persistence concerns — unlike entities, which are mutable and JPA-managed. On Java 21, a `record` gives constructor, accessors, `equals`/`hashCode`/`toString`, and immutability in one line, no Lombok needed.

### Security

**Q: `SecurityFilterChain` bean vs `WebSecurityConfigurerAdapter` — which is correct on current Spring Security?**
A: `WebSecurityConfigurerAdapter` is deprecated and removed in Spring Security 6.x. The only supported approach now is a `@Bean SecurityFilterChain` using the lambda DSL. A real gotcha if following older tutorials.

**Q: Why disable CSRF protection? Isn't that a security downgrade?**
A: No — it's correct for this architecture, not a shortcut. CSRF defends against a browser automatically attaching ambient credentials (session cookies) to a forged cross-site request. This API is stateless-JWT-bound: a JWT must be explicitly attached by client code in a header, never sent automatically by the browser. CSRF isn't a relevant threat model for token-in-header auth, so disabling it removes a protection that doesn't apply here.

**Q: Why set `SessionCreationPolicy.STATELESS` before real authentication even exists?**
A: The target architecture (Spring Security + JWT) is already known. Declaring statelessness now avoids Spring Security defaulting to session-based behavior and having to unwind that later — doesn't add any auth logic, just correctly shapes session handling from the start.

### Domain Modeling

**Q: Why are `Transaction` and `Payment` two separate entities instead of one?**
A: `Transaction` is the internal wallet ledger — an append-only record of every DEBIT/CREDIT movement *inside* BusLink. `Payment` is an external gateway interaction — money crossing the boundary between the rider's bank/UPI/card and BusLink. A wallet top-up is a `Payment` that *causes* a `Transaction` (CREDIT). A direct UPI/card fare payment is a `Payment` with **no** `Transaction` at all, since it never touches the wallet. Conflating them breaks the moment direct non-wallet payment needs supporting.

**Q: Why does `Ticket` have both `issuedAt` and an inherited `createdAt` — isn't that redundant?**
A: Not in an offline-first system. `createdAt` = when the row was persisted to the server. `issuedAt` = when the conductor's device actually issued the ticket in real-world time. A ticket created offline and synced later has genuinely different values for these two. `issuedAt` must be client-settable (the server doesn't know true issuance time); `createdAt` stays server-controlled via `@CreationTimestamp`.

**Q: Why does `TicketStatus` have both `ISSUED` and `PAID` as separate states?**
A: Reflects the real flow: a conductor scans a rider's QR to identify *whose account* to send the ticket to (not to validate a trip like a metro gate — buses here have no entry/exit gates), enters stops manually, and the ticket is created. Payment happens as a **separate, later step** (wallet or online mode). `ISSUED` = created, awaiting payment; `PAID` = payment completed; `EXPIRED` covers an issued-but-never-paid ticket (fare evasion); `CANCELLED` for conductor/admin voids.

**Q: Why does `Conductor` get its own `ConductorStatus` enum instead of reusing `UserStatus`, even though the values are identical (`ACTIVE`/`INACTIVE`/`SUSPENDED`)?**
A: Conductor and rider lifecycles are different domain concepts that happen to share the same *shape* today. Coupling them to one type means a future change to one (e.g. adding a conductor-only `ON_LEAVE` state) forces a decision about whether it applies to riders too. Keeping them separate costs nothing now and avoids that coupling later.

**Q: Why does `Route` only store `originStop`/`destinationStop` instead of the full ordered list of stops?**
A: Nothing in this sprint (no business logic, no fare calculation) consumes a full stop sequence — `Ticket` already carries its own `sourceStop`/`destinationStop` per rider. Modeling the real stop topology (ordered stops + per-stop stage, needed for conductor-side fare-by-stage calculation) is deliberately deferred to whenever the Fare Service is actually built, rather than designed speculatively now. No Flyway migrations exist yet, so reshaping this later costs nothing.

**Q: Why is the `users` table named `users` and not `user`?**
A: `user` is a reserved keyword in PostgreSQL (tied to `CURRENT_USER`). An unquoted `CREATE TABLE user (...)` fails outright. `users` sidesteps the collision entirely — the standard practice specifically because of this keyword conflict. Every other table stays singular (`wallet`, `ticket`, etc.) — `users` is the one deliberate exception, not a new convention.

### API Design & Error Handling

**Q: Why one generic `ApiResponse<T>` envelope for every endpoint instead of returning raw objects?**
A: Without a shared contract, every controller invents its own response shape, and any client (frontend, Postman, another service) has to special-case every endpoint. One envelope (`success`/`message`/`data`) means every response — success or failure — follows the same predictable shape.

**Q: Why not use RFC 7807 `ProblemDetail` (Spring's built-in standard) for error responses?**
A: `ProblemDetail` is genuinely more "standard" for errors specifically, but it only covers the error path — it doesn't solve response-shape consistency for successful responses too. Using `ApiResponse` uniformly for both keeps one contract everywhere, which was judged more valuable than partial standards-compliance.

**Q: Why does `GlobalExceptionHandler` handle 6 exception types instead of just the 3 originally planned (`ResourceNotFoundException`, `ValidationException`, generic `Exception`)?**
A: Added 3 more based on what the codebase actually does: `MethodArgumentNotValidException` (Spring's own Bean Validation failure — without a handler, any future `@Valid` failure produces a raw 500 instead of a clean 400 with field errors), `DataIntegrityViolationException` (relevant given 6+ unique constraints already added across entities — a duplicate email otherwise surfaces as a raw 500 leaking SQL text), and `ObjectOptimisticLockingFailureException` (relevant given `Wallet.version` — without this, the whole point of adding `@Version` is undermined).

**Q: `ResourceNotFoundException` vs `ValidationException` vs `MethodArgumentNotValidException` — what's each actually for?**
A: `MethodArgumentNotValidException` is Spring's own, thrown automatically when `@Valid` Bean Validation annotations (`@NotNull`, `@Email`) fail on a request DTO. `ValidationException` (custom) is for validation those annotations *can't* express — cross-field or business-rule checks needing real logic (e.g. "mobile number isn't already registered," which needs a DB lookup). `ResourceNotFoundException` is for a lookup failure (404) — an expected, normal REST outcome, not a bug.

**Q: Why does the generic `Exception` handler return a vague message instead of the real exception text?**
A: Security: returning raw exception details (class names, stack traces, sometimes SQL fragments) to the client is information disclosure. The real exception is logged server-side via `log.error` for debugging; the client only sees a safe, generic message.

### Offline-First Design

**Q: What problem does `SyncEvent` actually solve?**
A: When a conductor's device creates a `Ticket` offline, something needs to track what was attempted, whether it succeeded once synced to the server, and why if it failed (so it can be retried/investigated) — the "outbox"/sync-log pattern. Without it, a failed sync just vanishes with no trace.

**Q: Why does `SyncEntityType` only have one value (`TICKET`)?**
A: `Transaction` and `Payment` are inherently online-only operations — a wallet debit needs to check the live balance server-side (what `@Version` protects), and a UPI/card payment needs to reach an external gateway over the network. Neither can be created in an offline state that would need syncing later. `Ticket` is the only entity actually created offline. A single-value enum still earns its keep over a raw `String` (compile-time safety, documents intent) and costs nothing to extend if a future offline-creatable entity appears.

---

## Sprint 2

### Security

**Q: Why does `JwtUtil` embed a `"type"` claim (`access`/`refresh`) in every token instead of just using expiry to tell them apart?**
A: Without it, a leaked long-lived refresh token (7-day expiry) could be sent directly as a Bearer access token against any protected endpoint — it would pass signature and expiry checks just fine, defeating the point of a short-lived access token. `JwtAuthenticationFilter` checks `isRefreshToken()` and rejects the request outright if a refresh token is used where an access token belongs. Found *after* the filter was first written (S2-04), fixed once the gap was noticed while building `refreshToken()` (S2-14) — a security property that isn't obvious until you think adversarially about what a stolen token can be used for.

**Q: Why does `login()` — and `UserDetailsServiceImpl.loadUserByUsername()` — deliberately avoid revealing whether a failure was "email not found" vs "wrong password"?**
A: Distinguishable errors (e.g. 404 for a missing email, 400 for a wrong password) let an attacker enumerate registered emails by testing `/auth/login` with guessed addresses. Two places guard against this independently: `loadUserByUsername()` throws Spring Security's own `UsernameNotFoundException` (which `DaoAuthenticationProvider` translates into a generic auth failure — the project's own `ResourceNotFoundException` wouldn't get that translation), and `AuthServiceImpl.login()` — a hand-rolled flow calling `passwordEncoder.matches()` directly, so it doesn't inherit that protection automatically — throws the same `ValidationException("Invalid email or password")` (400) for both cases.

**Q: Why is `UserPrincipal` a separate adapter class instead of making `User` implement `UserDetails` directly?**
A: Keeps Spring Security framework types out of the `entity/` package, consistent with every other entity staying framework-agnostic. The adapter wraps a `User` and grants a fixed `ROLE_PASSENGER` authority (every row in `User` is a rider; `Conductor` will be a separate entity/auth path in Sprint 3).

**Q: Unauthenticated requests to a protected endpoint returned `403` instead of the expected `401` — what was actually wrong?**
A: `ExceptionTranslationFilter` needs an `AuthenticationEntryPoint` to call when authentication is missing. With no `httpBasic()`/`formLogin()` enabled (correct for a stateless JWT API) and no custom entry point configured, it silently fell back to `Http403ForbiddenEntryPoint`. `401` ("who are you") and `403` ("I know who you are, but no") are semantically different, and a missing credential is unambiguously the former. Fixed by adding `JwtAuthenticationEntryPoint` and wiring it via `.exceptionHandling(ex -> ex.authenticationEntryPoint(...))`.

**Q: Duplicate-email registration returned `400` instead of `409` — what was the actual bug?**
A: `AuthServiceImpl.register()`'s duplicate-email check threw `ValidationException`, which `GlobalExceptionHandler` unconditionally maps to `400`. But "this resource already exists" is a conflict with existing server state (409), not a malformed request (400). Fixed by adding a dedicated `ConflictException` → `409` mapping, used only for this case; `login()`/`refreshToken()` keep `ValidationException`/400 since those genuinely are bad requests.

### JWT Fundamentals

**Q: What are the three parts of a JWT, and what does each one actually do?**
A: `header.payload.signature`, base64url-encoded and dot-separated.

```
 eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJhQGIuY29tIn0 . 5mZ2f9K...
 └─────────┬──────────┘   └──────────┬──────────┘   └───┬───┘
        HEADER                    PAYLOAD            SIGNATURE
    {"alg":"HS256",          claims: sub (email),   HMAC(header+payload, secret)
     "typ":"JWT"}            iat, exp, "type"        proves it wasn't tampered
                                                       with — NOT that it's secret
```

Base64 is an *encoding*, not encryption — anyone can decode the payload. The signature only guarantees integrity, which is why no secrets ever go in the claims, only the email as subject.

**Q: Why issue two tokens (access + refresh) instead of one?**
A: Splits a trade-off. One long-lived token means fewer re-logins, but a stolen token stays dangerous for its whole lifetime. The **access token** is short-lived and does the real work (sent on every request, most exposed to interception) — if stolen, its usefulness expires fast. The **refresh token** is long-lived but rarely transmitted (only to get a new access token), so its bigger blast radius is offset by a much smaller exposure surface.

```
 LOGIN → issue accessToken (short) + refreshToken (long)
   │
   ▼
 client sends accessToken on every request ──► eventually expires
                                                     │
                                                     ▼
                                     POST /auth/refresh with refreshToken
                                                     │
                                                     ▼
                                     server validates it, issues a new
                                     accessToken (loop until refreshToken
                                     itself expires → full re-login)
```

### Spring Security Filter Chain Fundamentals

**Q: Step by step, what happens when a request hits a protected endpoint like `GET /user/profile`?**
A:

```
 HTTP Request
     │
     ▼
 JwtAuthenticationFilter          ← custom, registered BEFORE
  - reads Authorization header      UsernamePasswordAuthenticationFilter
  - validates the JWT
  - populates SecurityContext
     │
     ▼
 ExceptionTranslationFilter       ← catches Authentication/AccessDenied
     │                              exceptions thrown further down
     ▼
 AuthorizationFilter              ← evaluates authorizeHttpRequests()
     │
     ▼
 unauthenticated + auth required? ──yes──► JwtAuthenticationEntryPoint
     │no                                    .commence() → 401 JSON body
     ▼
 DispatcherServlet → Controller
```

**Q: What's the practical difference between authentication and authorization here, and where does each happen?**
A: Authentication = "who is this" — proven per request by `JwtAuthenticationFilter`, which populates `SecurityContextHolder` with a `UserPrincipal`. Authorization = "are they allowed to do this" — decided declaratively in `SecurityConfig.authorizeHttpRequests()`. Today that's binary (`permitAll()` vs `authenticated()`) since every `User` only ever carries `ROLE_PASSENGER` — the role-based machinery (`GrantedAuthority`, `hasRole()`) is wired but won't really be exercised until Sprint 3's conductor auth adds a second role. Also worth knowing: under `SessionCreationPolicy.STATELESS`, none of this is persisted in an `HttpSession` — the JWT re-proves identity from scratch on every request.

### Password Security Fundamentals

**Q: Why hash passwords with BCrypt instead of encrypting them, or using a fast hash like SHA-256?**
A: Encryption is reversible — a compromised key means every password is recoverable, and login never needs plaintext back, only "does this match." A fast hash like SHA-256 is built for data-integrity checks, not secrecy — its speed lets an attacker brute-force billions of candidates/sec against a leaked hash. BCrypt is deliberately slow and tunable (exponential cost factor, library default 10) with salting built in, so brute-forcing stays expensive even at scale, and identical passwords never produce identical stored hashes.

**Q: How does `BCryptPasswordEncoder.matches()` verify a password without ever storing the plaintext?**
A: A BCrypt hash embeds its own random salt in the stored string itself (`$2a$10$<salt><hash>`). `matches(raw, encoded)` extracts that salt, re-runs BCrypt on the candidate password with it, and compares the two resulting hashes.

```
 REGISTER: hash(pw, freshRandomSalt) → store "$2a$10$saltHASH..."
 LOGIN:    read salt out of the STORED hash → hash(candidate, thatSalt)
           → compare to stored hash → match / no match
```

### HTTP Status Code Semantics

**Q: When should an endpoint return 400 vs 401 vs 403 vs 409 — and where does each show up in BusLink?**
A:

| Code | Meaning | Where it happens in BusLink |
|---|---|---|
| 400 Bad Request | Malformed request or failed business-rule check | `ValidationException` — wrong login credentials, inactive account |
| 401 Unauthorized | "Who are you?" — no/invalid credentials | `JwtAuthenticationEntryPoint` — missing/expired/garbage JWT |
| 403 Forbidden | "I know who you are, but not allowed" | Not yet reachable — needs role-restricted endpoints (Sprint 3) |
| 409 Conflict | Conflicts with existing server state | `ConflictException` — duplicate email at registration |

### Boot 4 / Framework Gotchas

**Q: Constructor-injecting `com.fasterxml.jackson.databind.ObjectMapper` compiled fine but failed at startup with "no bean of that type" — why?**
A: Spring Boot 4.1's default JSON engine is **Jackson 3**, under a new package (`tools.jackson.databind.ObjectMapper`) — `spring-boot-starter-jackson` only autoconfigures a bean of that type. Classic Jackson 2 classes were still resolvable at compile time because `jjwt-jackson` pulls them in transitively for jjwt's own internal use, but that's never a Spring-managed bean. A class on the classpath and a class having a Spring bean are different questions. Fix: inject `tools.jackson.databind.ObjectMapper` instead.

**Q: Where did `UsernamePasswordAuthenticationFilter` move, and why does it matter?**
A: Spring Security 7.x (Boot 4.1.0) moved it from `org.springframework.security.authentication` to `org.springframework.security.web.authentication`. Boot-3-era tutorials using the old import fail to compile as-is.

### Testing Strategy

**Q: Why is `AuthServiceImplTest` a plain Mockito unit test (`@Mock`/`@InjectMocks`, no Spring context) instead of `@SpringBootTest` + `@MockBean`?**
A: Nothing under test needs Spring wiring — `AuthServiceImpl`'s logic sits behind an interface boundary to its four collaborators (`UserRepository`, `WalletRepository`, `PasswordEncoder`, `JwtUtil`). Booting a full Spring context would be slower for no correctness benefit, and this environment has no DB/Docker connectivity to back a real context anyway.

**Q: How do you unit-test a method that calls `repository.save()` and then reads the ID Hibernate would generate, without a real database?**
A: Stub `save()` with Mockito's `thenAnswer` to mutate and return the same entity instance passed in, setting a fixed UUID before returning — mirrors real Hibernate behavior for `GenerationType.UUID`, where the ID is generated client-side *before* the insert, unlike an auto-increment `IDENTITY` strategy.

---

## Sprint 3

### Spring Data JPA

**Q: `RouteRepository extends JpaRepository<Route, UUID>` gives you `save()`/`findById()`/`findAll()` for free — where's the class that actually implements them?**
A: There isn't one in the source tree. `JpaRepository` is a chain of interfaces (`JpaRepository` → `PagingAndSortingRepository` → `CrudRepository`), and `save`/`findById`/etc. are just abstract method signatures declared there. At application startup, Spring Data scans for interfaces extending `JpaRepository` and generates a proxy implementation for each one at runtime (`SimpleJpaRepository`, wrapped in a JDK dynamic proxy) — that generated object is what gets injected wherever you ask for `RouteRepository`. Declaring the interface extension is a contract, not a copy-paste of code; Spring fulfills the contract with an object you never see the source of.

**Q: What's the actual difference between the Spring Data keywords `Containing` and `StartingWith` in a derived query method name, and why did mixing them up matter here?**
A: `Containing` compiles to `LIKE %x%` (substring anywhere); `StartingWith` compiles to `LIKE x%` (prefix only). A stop-search method declared with `Containing` when the actual requirement was a prefix match (`search=H` should return only stops *starting* with H) silently over-matched — 9 of 29 seeded stops contain an "h" mid-name (`Banashankari`, `Jayadeva Hospital`, `Marathahalli Bridge`, ...) that would have wrongly appeared in results. Caught by tracing the planned Postman assertions against the actual seed data before wiring the service layer to it, not by a failing test (none existed yet at that point).

**Q: Why does a malformed derived query method name (e.g. a typo'd property name) fail at Spring Boot *startup* rather than at compile time?**
A: The method name is just a `String` to `javac` — it compiles fine regardless of whether `RouteId`/`StopName`/etc. are real entity properties. Spring Data only *parses* the method name into a query (via `PropertyReferenceException` if it can't resolve a property) when it builds the repository proxy, which happens during `ApplicationContext` refresh at startup. This is why `./mvnw compile clean` passing doesn't prove a new/renamed derived query method actually works — only booting the app (or a `@DataJpaTest`) exercises that parsing step.

### Security

**Q: Walk through what happens end-to-end when a conductor logs in.**
A:
```
 POST /conductor/auth/login  { email, password }
              │
              ▼
 ConductorServiceImpl.login()
   1. conductorRepository.findByEmail(email)
        not found? ──► ValidationException("Invalid email or password")
   2. passwordEncoder.matches(rawPassword, conductor.passwordHash)   [BCrypt]
        wrong?     ──► same generic ValidationException  (no enumeration)
   3. conductor.status == ACTIVE ?
        no?        ──► ValidationException("Account is not active")
   4. conductor.busId != null ?
        no?        ──► ValidationException("Conductor is not assigned to a bus")
   5. busRepository.findById(busId)  → resolves routeId
              │
              ▼
 JwtUtil.generateConductorAccessToken() / generateConductorRefreshToken()
   - embeds  role: "CONDUCTOR"  claim in both tokens
              │
              ▼
 200 OK  { accessToken, refreshToken, conductorId, name, email, busId, routeId }
```
Same generic-error pattern as passenger login (Sprint 2) for steps 1–3 — an
attacker probing `/conductor/auth/login` can't distinguish "no such email"
from "wrong password" from "account disabled." Step 4 is a guard not in the
original plan: `Conductor.busId` is nullable in the schema, so "conductor
exists but isn't assigned a bus yet" is a real reachable state — without the
check, `busRepository.findById(null)` would surface as an unhelpful 500
instead of a clear 400.

**Q: Adding `ROLE_CONDUCTOR` as a second role required a second `UserDetailsService` bean (`ConductorDetailsServiceImpl` alongside `UserDetailsServiceImpl`) — what broke, and how was it resolved?**
A: Spring logs `Found 2 UserDetailsService beans ... Global Authentication Manager will not use a UserDetailsService for username/password login` — harmless here since neither `AuthServiceImpl.login()` nor `ConductorServiceImpl.login()` go through Spring's global `AuthenticationManager` (both call `passwordEncoder.matches()` directly). The real problem was in `JwtAuthenticationFilter`: injecting by the shared `UserDetailsService` *interface* type gives Spring two ambiguous candidates for one field (`NoUniqueBeanDefinitionException` at startup). Resolved by injecting both concrete classes directly — `@Qualifier` was the alternative, rejected as unnecessary ceremony for exactly two fixed, known implementations.

**Q: A role-mismatch request (valid JWT, wrong role) returned a bare `403` that bypassed the project's `ApiResponse` envelope — what was different from Sprint 2's missing-`401` bug, and how was each fixed?**
A: Two different Spring Security extension points, for two different failure modes. `AuthenticationEntryPoint` (Sprint 2's `JwtAuthenticationEntryPoint`) handles "not authenticated at all" → `401`. `AccessDeniedHandler` handles "authenticated, but not allowed" → `403` — Spring Security's *default* `AccessDeniedHandler` doesn't wrap the response in the app's `ApiResponse` shape, so it was the one inconsistent error format in the whole API. Fixed by adding `JwtAccessDeniedHandler` (mirrors `JwtAuthenticationEntryPoint` exactly) and wiring both: `.exceptionHandling(ex -> ex.authenticationEntryPoint(...).accessDeniedHandler(...))`.

**Q: How does `JwtAuthenticationFilter` decide whether to load a `UserPrincipal` or a `ConductorPrincipal` for a given token?**
A: `JwtUtil` bakes a `role` claim into every token at issuance (`generateAccessToken` for passengers vs `generateConductorAccessToken` for conductors — role is baked into *which method is called*, not passed as a raw string parameter, so a caller can't accidentally issue the wrong role for a given principal type). The filter extracts that claim and routes to the matching `UserDetailsService`. A token missing the claim entirely (any token issued before this existed) falls through to the passenger path — preserves backward compatibility with already-issued tokens rather than rejecting them.

**Q: Full request lifecycle for Sprint 3 — extend Sprint 2's filter-chain diagram to show where role-routing and the two failure modes (401 vs 403) actually happen.**
A:
```
 HTTP Request  (Authorization: Bearer <jwt>, or none)
              │
              ▼
 JwtAuthenticationFilter
   - reads Authorization header (no header → skip, stays unauthenticated)
   - validates signature + expiry
   - extractRole(token) → "PASSENGER" | "CONDUCTOR" | (missing → defaults PASSENGER)
              │
       ┌──────┴──────┐
       │             │
  "PASSENGER"    "CONDUCTOR"
       │             │
       ▼             ▼
 UserDetailsServiceImpl   ConductorDetailsServiceImpl
       │             │
       ▼             ▼
 UserPrincipal    ConductorPrincipal        → populates SecurityContextHolder
 (ROLE_PASSENGER)  (ROLE_CONDUCTOR)
              │
              ▼
 AuthorizationFilter — evaluates SecurityConfig.authorizeHttpRequests()
   /admin/**            → hasRole(ADMIN)
   /conductor/**         → hasRole(CONDUCTOR)   (except /conductor/auth/login → permitAll)
   /routes/*/stops,/fare → hasRole(CONDUCTOR)
   everything else       → authenticated
              │
     ┌────────┼─────────────────┐
     │        │                 │
 no/invalid  valid token,    authorized
 token       wrong role          │
     │        │                 ▼
     ▼        ▼           DispatcherServlet → Controller
 JwtAuthentication  JwtAccessDeniedHandler
 EntryPoint         .handle() → 403
 .commence() → 401  {success:false,
 {success:false,     message:"Access is denied",
  message:...,       data:null}
  data:null}
```
The two handlers are easy to conflate but answer different questions:
`AuthenticationEntryPoint` = "I don't know who you are" (`401`);
`AccessDeniedHandler` = "I know who you are, but you can't do this" (`403`).
Both had to be wired to keep every error response in the same `ApiResponse`
envelope — Spring Security's *default* handler for the second case doesn't
know about `ApiResponse` at all.

### Domain Modeling & Denormalization

**Q: `Route.totalStops`/`destinationStop` are denormalized (computable from `RouteStop`, but stored directly on `Route`) — what's the real cost of that choice?**
A: The benefit is cheap reads — `GET /admin/routes` doesn't need a join/count query per route. The real cost, and the thing worth knowing for an interview: denormalized fields must be updated by *every* write path that touches the source data, or they silently go stale. `createRoute` sets them once from the full stop list; `addStop` — added later, once a "add one stop to an existing route" endpoint existed — had to remember to bump `totalStops` and overwrite `destinationStop` too, or the fields would drift out of sync the first time a stop was added outside of route creation.

**Q: Why is adding a stop to an existing route restricted to append-only (`stopSequence` must equal the current `totalStops + 1`) instead of allowing insertion at any position?**
A: A route is a physically ordered line of stops. Inserting into the middle would mean renumbering every `stopSequence` (and often `stageNumber`) after the insertion point — a structural operation this endpoint was never scoped to do. Appending past the current end is the realistic real-world operation (a transit authority extending a route by one more stop), so the validation deliberately narrows to that case rather than attempting general reordering.

**Q: `stopSequence` and `stageNumber` on `RouteStop` look similar — what's actually different about them, and why do you need both?**
A: `stopSequence` is a pure ordinal (1, 2, 3, ... — physical position along the route, always unique per route). `stageNumber` is the fare-grouping a stop belongs to — multiple physical stops can share one stage (the seeded data has 2 stops per stage almost throughout). Fare calculation (`stagesCrossed = destStage - originStage + 1`) needs `stageNumber`; ordering/appending logic needs `stopSequence`. Conflating them would make it impossible to model "two stops close together that cost the same fare to cross between."

**Q: A stop submitted with the correct next `stopSequence` but a `stageNumber` *lower* than the route's current last stop was still wrong — why, given the sequence check already passed?**
A: `stopSequence` only proves the new stop claims the next available ordinal slot — it says nothing about whether that slot is geographically last. A lower `stageNumber` than the current last stop means the new stop actually belongs somewhere in the *middle* of the route's fare progression, not at the end — same renumbering problem as inserting mid-sequence, just disguised by a technically-valid sequence number. Caught by reasoning about what the fields actually represent, not by a failing test (none existed for this case until it was found).

**Q: Walk through `addStop`'s full validation flow — what has to be true for a stop to actually get appended?**
A:
```
 POST /admin/routes/{routeId}/stops  { stopName, stopSequence, stageNumber }
              │
              ▼
 routeRepository.findById(routeId)
    not found?                          ──► ResourceNotFoundException (404)
              │
              ▼
 routeStopRepository.findByRouteIdAndStopName(routeId, stopName)
    already exists?                     ──► ConflictException (409)
              │
              ▼
 stopSequence == route.totalStops + 1 ?
    no  (gap, or not the next slot)     ──► ValidationException (400)
              │ yes
              ▼
 stageNumber >= currentLastStop.stageNumber ?
    no  (belongs mid-route, would need
         renumbering everything after)  ──► ValidationException (400)
              │ yes (same stage extends it, higher stage starts a new one)
              ▼
 save RouteStop
 route.totalStops += 1
 route.destinationStop = stopName        ← denormalized fields kept in sync
              │
              ▼
 200 OK  RouteStopResponseDTO
```
Four independent guards, each catching a different way the request could be
structurally wrong — worth walking through in order during an interview
since each one maps to a concrete failure mode that was reasoned through
rather than assumed away (see the two Q&As above for *why* the sequence
check alone isn't sufficient).

**Q: Walk through `calculateFare` end-to-end, with a worked example.**
A:
```
 GET /routes/{routeId}/fare?origin=HSR Layout&destination=KR Puram Railway Station
                            &adults=2&children=1&infants=1
              │
              ▼
 routeStopRepository.findByRouteIdAndStopName(routeId, origin)
    not found?                          ──► ResourceNotFoundException (404)
 routeStopRepository.findByRouteIdAndStopName(routeId, destination)
    not found?                          ──► ResourceNotFoundException (404)
              │
              ▼
 destination.stopSequence > origin.stopSequence ?
    no                                  ──► ValidationException
                                             ("Destination must be after origin")
              │ yes
              ▼
 routeRepository.findById(routeId)  → route.farePerStage
              │
              ▼
 stagesCrossed = (destination.stageNumber - origin.stageNumber) + 1
 adultFare     = stagesCrossed × farePerStage
 childFare     = adultFare / 2              (RoundingMode.CEILING, scale 2)
 infantFare    = 0
 totalFare     = adults × adultFare + children × childFare
              │
              ▼
 200 OK  FareResponseDTO

 Worked example — HSR Layout (stage 5) → KR Puram Railway Station (stage 10),
 farePerStage = 6.00, 2 adults + 1 child + 1 infant:
   stagesCrossed = (10 - 5) + 1 = 6
   adultFare     = 6 × 6.00     = 36.00
   childFare     = 36.00 / 2    = 18.00
   totalFare     = 2×36.00 + 1×18.00 = 90.00   (infant contributes 0)
```

### API Design

**Q: Why does `GET /routes/{routeId}/stops` handle three different behaviors (full list / prefix search / forward search) on one path instead of three separate endpoints?**
A: Matches how the sprint's plan grouped them — all three are "give the conductor a stop list," differentiated only by which optional query params (`search`, `after`) are present. The controller branches on presence/absence of those params rather than dispatching to three URLs. Trade-off: one endpoint to secure/document/test instead of three, at the cost of a less RESTfully "pure" single-responsibility-per-URL shape — judged acceptable since the three behaviors are genuinely one concept (stop listing) with optional narrowing, not three unrelated resources.

### Testing Strategy

**Q: `RouteServiceImplTest` wasn't in the sprint's original task list or Definition of Done — why did it get built anyway?**
A: The sprint's own **Scope** section (drafted before the detailed task list) named both `FareServiceImplTest` and `RouteServiceImplTest` as in-scope unit tests, but only `FareServiceImplTest` made it into an actual task number and DoD line — an inconsistency between the high-level scope and the detailed checklist that's easy to miss if you only ever check the task list. Caught by comparing Scope against Tasks/DoD directly, confirmed with the project owner, and closed rather than left as a silently-unfulfilled scope line.

### Offline-First Design

**Q: If the conductor app is meant to cache the full stop list locally at login (per this project's offline-first design goal), why do `searchStops`/`searchStopsAfter` exist as separate backend endpoints instead of the client just filtering its own cache?**
A: They're the server-side source of truth the client's local cache gets built from, and the only way to verify the search/filter behavior at all before a real client exists (Postman exercises them directly). They aren't meant to be called per-keystroke by a well-built offline-capable client — a bus can lose connectivity mid-route, so the real destination-dropdown experience should filter the already-cached ~29 stops entirely client-side, not round-trip to the server on every character typed. At this route's actual scale (≤29 rows, indexed columns), even a naive per-keystroke caller wouldn't stress the backend — the real reason to avoid it is network reliability on a moving bus, not server load.

---

## Sprint 4

### Idempotency

**Q: What problem does `X-Idempotency-Key` actually solve on `POST /tickets/issue`, and why not just rely on the client not double-submitting?**
A: A conductor's device is on a moving bus — a network blip mid-request means the client genuinely doesn't know if the server received it, so a retry is the *correct* client behavior, not a bug. Without idempotency, that retry creates a second ticket for the same boarding. The client generates a UUID once per logical issuance attempt and resends the *same* key on retry; the server treats "same key seen again" as "return what I already did," not "do it again."

**Q: Walk through the idempotency check's three branches.**
A:
```
 idempotencyKeyRepository.findByKey(key)
              │
     ┌────────┼─────────────────┐
     │        │                 │
  not found   found,         found,
              expiresAt      expiresAt
              in future      in the past
     │        │                 │
     ▼        ▼                 ▼
  proceed   fetch the        delete the stale
  normally  original ticket  key, proceed as
            by its stored    a brand-new
            ticketId, return request
            it unchanged,
            create nothing
            new
```
The TTL (24h, `ticket.idempotency.ttl-hours`) exists so a key isn't held forever — a
genuinely new issuance attempt reusing an old, expired key (unlikely, but the client
UUID space is large, not zero) should be treated as new, not permanently blocked.

**Q: Why store just `ticketId` on `IdempotencyKey` instead of caching the full response body?**
A: The `Ticket` row is already the source of truth — storing a second, potentially
stale copy of its shape would need to be kept in sync with the entity forever for no
benefit. `IdempotencyKey` only needs to answer "have I seen this key, and if so, which
ticket did it produce" — a foreign-key-shaped pointer, not a cache.

**Q: This project already has `Wallet.version` (optimistic locking, Sprint 1) for protecting against concurrent double-writes. Why isn't that the same mechanism used for idempotency here?**
A: They solve different problems. Optimistic locking protects a single row from being
overwritten by two racing *writers* to the *same* record. Idempotency protects against
one *logical* request being executed twice — the two `POST /tickets/issue` calls in
this sprint's Postman flow aren't racing each other for the same row; they're the same
conceptual request arriving twice, sequentially, and the second one shouldn't create a
row at all. A version check can't express "don't do this a second time" — only
"don't let a stale read win."

### Spring Configuration Properties

**Q: `WalletProperties`/`TicketProperties` are Java `record`s with `@ConfigurationProperties` — what's actually different from binding with a Lombok `@Getter`/`@Setter` POJO?**
A: Records are immutable and use constructor binding — Spring populates every field via
the canonical constructor at creation time, so there's no window where the object
exists half-configured. A mutable POJO binds via setters called one at a time, which
matters more for larger/nested config, but the immutability alone is reason enough for
values (like an overdraft limit) that should never change after startup.

**Q: A dotted property key like `wallet.overdraft.limit` failed to bind to a flat field `overdraftLimit` — no error, no exception, it just silently resolved to the default (`null`). Why does that happen instead of failing loudly?**
A: Spring's relaxed binding equates casing styles (`camelCase`/`kebab-case`/
`snake_case`) *within one property segment* — `overdraft-limit` and `overdraftLimit`
are the same segment, just styled differently, so those bind fine. But a literal `.` in
a key always means *another level of nesting* to the binder, never a word boundary —
`wallet.overdraft.limit` describes a `wallet.overdraft` object with a `limit` field,
which doesn't exist here. Since nothing *requires* every property to bind to something,
an unmatched key is just silently ignored rather than erroring — which is exactly why
this class of bug doesn't show up until runtime (an NPE deep inside business logic),
not at startup. Proved this empirically — re-ran the same binding test with the
original dotted key and confirmed it really does resolve to `null` — rather than just
asserting it from reading the binder's docs.

### JPA / Hibernate Gotchas

**Q: Adding `TicketStatus.TERMINATED` to the Java enum compiled fine and passed every mocked unit test, but broke at the database layer the first time a real terminate call ran. What was actually going on?**
A: Hibernate had generated a `CHECK` constraint on the `status` column back when the
table was first created (`ddl-auto=create`, Sprint 1), hardcoded to the enum's literal
values *at that point in time*. `ddl-auto=update` — used for every schema change since —
only ever adds new tables/columns; it never inspects or alters an existing constraint.
So the Java-side enum and the DB-side constraint silently drifted apart the moment a
5th value was added, and nothing in the compile-and-test pipeline could catch it,
because mocked repositories never touch a real constraint at all.
```
 Sprint 1 (ddl-auto=create):
   CHECK (status IN ('ISSUED','PAID','EXPIRED','CANCELLED'))    ← baked in once

 Sprint 4 (ddl-auto=update):
   TicketStatus.TERMINATED added to the Java enum                ← compiles fine
   ddl-auto=update adds new COLUMNS only, never touches           ← constraint
   an existing CHECK constraint                                     unchanged

 First real terminateTicket() call:
   UPDATE ticket SET status='TERMINATED' ...
   ──► ERROR: violates check constraint "ticket_status_check"     ← only surfaces
                                                                       against a
                                                                       real DB
```
**Q: How was this actually confirmed as a real bug rather than a theoretical one, before spending time fixing it?**
A: A rollback-wrapped `INSERT ... status='TERMINATED'` run directly against Postgres —
`BEGIN; INSERT ...; ROLLBACK;` — reproduces the exact failure with zero risk of leaving
bad data behind, and proves the gap empirically rather than by reading the schema and
assuming. Same "prove it before fixing it" pattern as the earlier `WalletProperties`
dotted-key gap.

### Concurrency in Practice

**Q: `Wallet.version` (`@Version`) was added all the way back in Sprint 1, purely as a designed-in protection. What changed in Sprint 4?**
A: Sprint 4 is the first time `WalletServiceImpl.payViaWallet()` actually performs a
real read-modify-write against `Wallet.balance` — before this sprint, nothing in the
codebase ever wrote to a wallet after its initial zero-balance creation at registration.
The optimistic-lock protection existed unexercised for 3 sprints; this is the sprint
where it does real work. No new locking code was needed at all — `walletRepository
.save(wallet)` automatically throws `ObjectOptimisticLockingFailureException` if
another transaction already bumped the version, and `GlobalExceptionHandler` (wired in
Sprint 2, before there was even anything to protect) turns that into a `409`.

**Q: Why does `payViaWallet` allow the wallet balance to go *negative* (overdraft) instead of just rejecting any payment that would exceed the balance?**
A: Real-world buses can't easily let a passenger un-board because their wallet is ₹5
short of the fare — the trip already happened. An overdraft limit (`wallet.overdraft
-limit`, default ₹100) lets the fare go through as long as `balance + overdraftLimit >=
totalFare`, trading a small amount of collection risk for not stranding a passenger
mid-journey. The check is a single comparison against an *effective* balance
(`wallet.balance + overdraftLimit`), not two separate branches for "has enough" vs
"needs overdraft" — the math is identical either way.

### Domain & API Design

**Q: `IssueTicketResponseDTO` and `TicketDetailResponseDTO` share almost every field (`ticketId`, fares, stops, status, `issuedAt`...) — why two DTOs instead of one shared shape?**
A: They serve different audiences at different moments. `IssueTicketResponseDTO` is
what a *conductor* sees the instant they issue a ticket — no need for
`conductorName`/`busNumber`/`routeNumber` since the conductor already knows those about
themselves. `TicketDetailResponseDTO` is what a *passenger* sees later, reviewing
history — they need that context spelled out, plus `paidAt`, which doesn't exist yet at
issuance time. Reusing one DTO for both would either force wasted enrichment lookups on
every issuance (irrelevant to the conductor) or leave the passenger view missing
context it actually needs. Same reasoning `TicketDetailResponseDTO`'s design doc
entry from S4-09 gives — a dedicated DTO per view, not one shape stretched to fit two.

**Q: Walk through `issueTicket`'s full validation chain, in order — what does each check actually prevent?**
A:
```
 POST /tickets/issue  (X-Idempotency-Key header + body)
              │
              ▼
 idempotency check           ← prevents a network retry double-issuing
              │
              ▼
 userRepository.findByQrToken   not found?  ──► 404 "Passenger not found"
              │
              ▼
 passenger.status == ACTIVE?    no?         ──► 400 "not active"
              │
              ▼
 conductor.busId == request.busId?  no?     ──► 400 "Bus mismatch"
              │                                  (prevents a conductor issuing
              ▼                                   tickets for another bus's route)
 bus.routeId == request.routeId?    no?     ──► 400 "Route mismatch"
              │
              ▼
 origin/destination stops exist on route?  no? ──► 404
              │
              ▼
 destination.stopSequence > origin's?  no?  ──► 400 "must be after origin"
              │
              ▼
 calculate fare, save Ticket (ISSUED), save IdempotencyKey    [@Transactional]
              │
              ▼
 200 OK  IssueTicketResponseDTO
```
Six independent guards before a single row is written — each one closes off a
different way a malformed or malicious request could otherwise corrupt state (a
passenger issuing themself a ticket, a conductor billing another bus's route, a
backwards fare calculation from a reversed origin/destination).

### Testing Strategy

**Q: `TicketServiceImplTest` mocks 8 collaborators (7 repositories + `TicketProperties`) for one class — is that a smell?**
A: It reflects the real complexity of `issueTicket`'s validation chain — 6 different
domain concepts (passenger, conductor, bus, route, stop, idempotency key) genuinely
need to be checked before a ticket can be created, and each is its own repository.
Mocking all of them is the correct unit-test shape for that; the alternative (fewer
collaborators) would mean the validation logic itself was under-decomposed, not that
the test has too many mocks. The real signal to watch for is whether *tests* start
needing excessive setup to reach one specific branch — here each test still only stubs
the 2–4 calls relevant to what it's actually checking.

**Q: `TicketProperties` is a `record`, and the test mocks it with `@Mock`. Records are implicitly `final` — doesn't Mockito normally refuse to mock final classes?**
A: That restriction is from Mockito's *classic* mock maker (`mockito-core` pre-5.0),
which needed the separate `mockito-inline` artifact to mock final classes/methods.
Since Mockito 5.0, the inline mock maker is the *default* — no extra dependency, no
extra setup, `@Mock` on a `record` just works. Worth knowing as a version-specific fact,
not a general Mockito truth — an older project on Mockito 3/4 would need the extra step.

---

## Sprint 5

### Ports & Adapters (Gateway Abstraction)

**Q: Why put a `PaymentGatewayPort` interface in front of the Razorpay SDK instead of calling `RazorpayClient` directly from `PaymentServiceImpl`?**
A: Without it, business logic (create an order, confirm a payment) would be
entangled with *how Razorpay specifically* does those things — its SDK types,
its raw webhook JSON shape, its checked exceptions. `PaymentGatewayPort`
exposes only what the domain actually needs (`createOrder`,
`verifyWebhookSignature`, `parseWebhookEvent`, `getPublicKeyId`), and
`RazorpayGatewayAdapter` is the *only* class in the codebase importing
`com.razorpay.*`. This is Ports & Adapters (Hexagonal Architecture) applied
narrowly at one boundary — not a full rewrite of the app's layering, just
the one seam that's genuinely vendor-coupled today and could plausibly need
a second gateway later.

**Q: `parseWebhookEvent` returns a `GatewayWebhookEvent` record with just `type` and `gatewayOrderId` — why not hand back Razorpay's full parsed JSON?**
A: That's the actual point of a port: the adapter absorbs the vendor's
shape, the port exposes a minimal *projection* of only the facts business
logic needs — did it succeed, and which order. A generic "mirror of
Razorpay's JSON" would just relocate the coupling one layer up instead of
removing it; the next thing to touch that JSON would still need to know
Razorpay's field names.

**Q: The port's methods declare no checked exceptions, but the underlying Razorpay SDK calls (`OrderClient.create`, `Utils.verifyWebhookSignature`) both throw a checked `RazorpayException`. How does the adapter reconcile that?**
A: Differently per method, based on what "the safe default" means for each:
- `createOrder` — catches `RazorpayException`, rethrows as a new unchecked
  `PaymentGatewayException`, mapped to `502 Bad Gateway`. An order-creation
  failure is a real upstream problem the caller needs to know about.
- `verifyWebhookSignature` — catches `RazorpayException` and returns
  `false` (fail-closed) rather than propagating. If signature verification
  can't even run, the safe default is "treat as invalid" — an exception
  here must never accidentally let a webhook bypass the security check.

### Webhooks as a Trust Boundary

**Q: Why is `POST /webhooks/razorpay` `permitAll()` in `SecurityConfig` — doesn't that mean anyone can hit it?**
A: Anyone *can* hit it, but Spring Security JWT auth was never the right
tool here — Razorpay's own servers call this endpoint, and Razorpay has no
JWT to send. The actual security check is inside the handler: the raw
request body's HMAC signature (`X-Razorpay-Signature`) is verified against
a shared webhook secret only Razorpay and this backend know. An invalid or
missing signature is rejected (`400`) before any business logic runs. The
trust boundary moved from "which role does this caller have" (Spring
Security's usual job) to "can this caller prove it's Razorpay" (a
domain-specific check), which is why it lives in the gateway port, not the
filter chain.

**Q: Why does the webhook body arrive as a raw `String`, not a typed request DTO?**
A: HMAC signature verification is computed over the *exact raw bytes*
Razorpay sent. Deserializing into a DTO first (even to re-serialize it
later) risks the JSON not round-tripping byte-for-byte — different key
ordering, whitespace, or number formatting would produce a different
signature and cause every legitimate webhook to fail verification. The raw
`String` is verified first; only after that succeeds does the adapter parse
it into the minimal `GatewayWebhookEvent` projection.

### Idempotency at the Attempt Level, Not Just the Request Level

**Q: The idempotency guard originally read `if (payment.getStatus() != PENDING) return;` — plausible-looking, but it caused a real bug during live verification. What went wrong?**
A: Razorpay allows multiple payment *attempts* against one *order* (a
declined card, then a retry with another card) and sends a separate webhook
per attempt, not one per order. The first attempt's `payment.failed`
webhook flipped the order-keyed `Payment` row to `FAILED` — a state the
guard treated as "already processed, don't touch it again." When the
second attempt's real `payment.captured` webhook arrived later for the
*same order*, the guard silently dropped it, and the wallet was never
credited even though Razorpay's own dashboard showed the order `paid`.
```
 Attempt 1 (declined)         Attempt 2 (succeeds)
        │                            │
        ▼                            ▼
 payment.failed webhook       payment.captured webhook
        │                            │
        ▼                            ▼
 Payment.status = FAILED      guard: status != PENDING?
        │                       → true (it's FAILED) → SKIPPED
        └── wrongly treated as terminal ───────────────┘
                                                    ✗ wallet never credited
```
**Q: What's the actual fix, and why does it still preserve the original duplicate-webhook protection?**
A: Change the guard to only treat `SUCCESS` as terminal:
`if (payment.getStatus() == PaymentStatus.SUCCESS) return;`. A `FAILED`
attempt no longer blocks a later real success on the same order — but a
genuine duplicate of a `payment.captured` webhook (Razorpay's own retry
behavior) is still caught, since the first successful processing already
set `status = SUCCESS`. The fix narrows *what counts as done*, without
weakening *what counts as a duplicate*.

**Q: General lesson — what does this bug say about designing idempotency for any payment gateway integration, not just Razorpay?**
A: Identify the gateway's actual retry unit before choosing what your
idempotency key represents. Here, the natural assumption ("one payment
event per order") was wrong — the gateway's retry unit is the *attempt*,
and only one specific attempt outcome (success) is truly final for the
order. A guard written against "the first non-pending state wins" silently
encodes the wrong assumption; the fix was to encode the *actual* terminal
condition (`SUCCESS`) rather than an approximation of it ("not still
pending").

### Money Movement & Ledger Design

**Q: Walk through what actually happens, in order, when a recharge webhook confirms overdraft recovery.**
A:
```
 wallet.balance = -40.00, payment.amount = 200.00
              │
              ▼
 balance < 0?  yes
              │
              ▼
 Transaction(DEBIT, 40.00, referenceId=payment.paymentId)   ← acknowledges the
              │                                                 existing debt
              ▼
 wallet.balance = -40.00 + 200.00 = 160.00
              │
              ▼
 Transaction(CREDIT, 200.00, referenceId=payment.paymentId) ← the recharge itself
              │
              ▼
 payment.status = SUCCESS
```
Two `Transaction` rows, not one — the ledger records "the debt was repaid"
and "new money arrived" as separate, individually-auditable events, even
though only one `wallet.save()` actually happens.

**Q: `Transaction.referenceId` is `NOT NULL`, but the overdraft-recovery `DEBIT` doesn't correspond to any single ticket or event the way every other `Transaction` in the system does. What's actually stored there, and why?**
A: Both the recovery `DEBIT` and the recharge `CREDIT` reuse
`payment.getPaymentId()`. This reframes what `referenceId` means slightly —
not strictly "the ticket/entity this money is about" (its meaning
everywhere else, e.g. a ticket ID for a wallet-payment `DEBIT`), but "the
event that caused this ledger entry to be written." For overdraft recovery,
that's genuinely the same recharge `Payment` for both rows, so no schema
change (making the column nullable) or sentinel-value hack was needed —
just a slightly broader, still-truthful reading of the same column.
Alternatives considered and rejected: a nullable column (Sprint 4's
`ddl-auto=update`-can't-alter-constraints problem would apply identically
here), or a sentinel/marker UUID (a footgun for anyone querying the table
later without knowing the convention).
