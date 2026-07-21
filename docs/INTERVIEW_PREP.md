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
